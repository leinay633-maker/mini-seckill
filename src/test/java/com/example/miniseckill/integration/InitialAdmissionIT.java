package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.*;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.job.SeckillMessageRetryJob;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.service.impl.ActivityServiceImpl;
import com.example.miniseckill.service.impl.OrderServiceImpl;
import com.example.miniseckill.support.AdmissionHarness;
import com.example.miniseckill.util.RedisKeyUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real production SQL and Spring order transactions. Redis/MQ are deterministic fault doubles,
 * not a broker crash test or a capacity measurement. */
@Testcontainers(disabledWithoutDocker = true)
class InitialAdmissionIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mini_seckill").withUsername("miniseckill").withPassword("miniseckill");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private SeckillMessageMapper messages;
    private SeckillOrderMapper orders;
    private SkuStockMapper stocks;
    private ActivityServiceImpl activities;
    private OrderService orderService;
    private AdmissionHarness harness;
    private final AdmissionCapacityProperties capacity = new AdmissionCapacityProperties();
    private final SeckillMetrics metrics = new SeckillMetrics(new SimpleMeterRegistry());

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(new FileSystemResource("sql/init.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        for (String table : List.of("seckill_order", "seckill_message", "seckill_log", "sku_stock_segment", "sku_stock")) {
            jdbc.update("DELETE FROM " + table);
        }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(SeckillMessageMapper.class, SeckillOrderMapper.class, SeckillLogMapper.class,
                SkuStockMapper.class, SkuStockSegmentMapper.class, SeckillActivityMapper.class)) {
            configuration.addMapper(mapper);
        }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(SeckillMessageMapper.class);
        orders = session.getMapper(SeckillOrderMapper.class);
        stocks = session.getMapper(SkuStockMapper.class);
        activities = new ActivityServiceImpl(session.getMapper(SeckillActivityMapper.class), capacity, metrics);
        activities.create(1L, "admission-it", LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1));
        activities.start(1L);
        harness = new AdmissionHarness(messages, orders, activities, capacity, metrics);
        harness.properties.getMysqlStockSegment().setEnabled(false);
        AtomicLong ids = new AtomicLong(1_000_000L);
        OrderServiceImpl target = new OrderServiceImpl(orders, stocks, session.getMapper(SeckillLogMapper.class),
                messages, session.getMapper(SkuStockSegmentMapper.class), harness.redis, harness.properties, metrics,
                ids::getAndIncrement);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(OrderService.class);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        orderService = (OrderService) proxy.getProxy();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void durableSendingIsVisibleToAnotherConnectionBeforePublication(boolean optimized) {
        capacity.setInitialSendingEnabled(optimized);
        harness = new AdmissionHarness(messages, orders, activities, capacity, metrics);
        doAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            SeckillMessage message = invocation.getArgument(2);
            assertEquals(MessageStatus.SENDING.getCode(), independentStatus(message.getRequestId()));
            return null;
        }).when(harness.rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertEquals(0, harness.service.placeOrder(harness.request(10L), "test").getCode());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_message", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order", Integer.class));
    }

    @Test
    void persistedSendingWithoutInitialPublicationCanBeRecoveredByTheExistingRetryJob() {
        // Simulate the cut after durable INSERT by not calling sendInitial. No process was killed.
        assertEquals(1, messages.insertPending("cut-before-publish", 1L, 10L, 1001L, harness.producer.initialMessageStatus()));
        assertTrue(retryable().isEmpty(), "a live initial send lease must not be stolen");
        jdbc.update("UPDATE seckill_message SET send_lease_until=DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE request_id='cut-before-publish'");
        assertEquals("cut-before-publish", retryable().get(0).getRequestId());
        harness.properties.getMessageRetry().setEnabled(true);
        new SeckillMessageRetryJob(messages, mock(CompensationRecordMapper.class), harness.producer,
                harness.properties, metrics).retrySendMessage();
        verify(harness.rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        harness.producer.confirm(new CorrelationData("cut-before-publish|" + jdbc.queryForObject("SELECT send_token FROM seckill_message WHERE request_id='cut-before-publish'", String.class)), true, null);
        assertEquals(MessageStatus.SENT.getCode(), status("cut-before-publish"));
        assertEquals(1, claim("cut-before-publish"));
    }

    @Test
    void synchronousPublishFailureKeepsDurableRetryWorkWithoutReturningRedisStock() {
        doThrow(new AmqpException("injected send failure")).when(harness.rabbit)
                .convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertEquals(0, harness.service.placeOrder(harness.request(10L), "test").getCode());
        String id = onlyRequestId();
        assertEquals(MessageStatus.FAILED.getCode(), status(id));
        assertTrue(retryable().isEmpty(), "failed attempt honors its backoff");
        jdbc.update("UPDATE seckill_message SET next_retry_at=DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE request_id=?", id);
        assertEquals(id, retryable().get(0).getRequestId());
        verify(harness.values, never()).increment(anyString());
        verify(harness.redis, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fastConsumerCommitWinsOverLateConfirmOrSendFailure(boolean lateSendFailure) {
        stocks.upsertStock(1L, 1001L, 2);
        doAnswer(invocation -> {
            SeckillMessage message = invocation.getArgument(2);
            assertEquals(MessageStatus.SENDING.getCode(), independentStatus(message.getRequestId()));
            assertEquals(1, claim(message.getRequestId()));
            orderService.createOrderFromConsumingMessage(message);
            if (lateSendFailure) {
                throw new AmqpException("injected transport failure after consumer commit");
            }
            harness.producer.confirm(invocation.getArgument(4, CorrelationData.class), true, null);
            return null;
        }).when(harness.rabbit).convertAndSend(anyString(), anyString(), any(), any(MessagePostProcessor.class), any(CorrelationData.class));
        assertEquals(0, harness.service.placeOrder(harness.request(10L), "test").getCode());
        assertEquals(MessageStatus.CONSUMED.getCode(), status(onlyRequestId()));
        assertEquals(OrderStatus.SUCCESS.getCode(), orders.selectByUserSku(1L, 10L, 1001L).getStatus());
        assertEquals(1, stocks.selectBySkuId(1L, 1001L).getAvailableStock());
        assertEquals(1, stocks.selectBySkuId(1L, 1001L).getSoldCount());
        assertTrue(retryable().isEmpty());
        verify(harness.values, never()).increment(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SUCCESS", "FAILED"})
    void realMysqlOrderStillBlocksTokenWhenAllRedisDuplicateKeysAreMissing(OrderStatus existingStatus) {
        stocks.upsertStock(1L, 1001L, existingStatus == OrderStatus.SUCCESS ? 1 : 0);
        messages.insertPending("old-order", 1L, 10L, 1001L, MessageStatus.CONSUMING.getCode());
        SeckillMessage message = new SeckillMessage("old-order", 1L, 10L, 1001L, 1L);
        if (existingStatus == OrderStatus.SUCCESS) {
            orderService.createOrderFromConsumingMessage(message);
        } else {
            assertThrows(InsufficientStockException.class, () -> orderService.createOrderFromConsumingMessage(message));
            orderService.recordFailedOrder(message);
        }
        // The Redis double deliberately returns null/false: durable facts must still reject.
        assertEquals(409, harness.service.createOrderToken(1L, 10L, 1001L, "test").getCode());
        verify(harness.values, never()).setIfAbsent(eq(RedisKeyUtil.tokenKey(1L, 10L, 1001L)), anyString(), any(Duration.class));
        assertEquals(existingStatus.getCode(), orders.selectByUserSku(1L, 10L, 1001L).getStatus());
    }

    @Test
    void closingActivityAfterTokenPreventsANewAdmissionOnTheSameInstance() {
        assertEquals(0, harness.service.createOrderToken(1L, 10L, 1001L, "test").getCode());
        activities.close(1L);
        assertEquals(403, assertThrows(BusinessException.class,
                () -> harness.service.placeOrder(harness.request(10L), "test")).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_message", Integer.class));
        verifyNoInteractions(harness.rabbit);
    }

    private int claim(String requestId) {
        return messages.markConsuming(requestId, MessageStatus.CONSUMING.getCode(), MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(), MessageStatus.REPLAYED.getCode());
    }

    private List<SeckillMessageRecord> retryable() {
        return messages.selectRetryable(MessageStatus.PENDING.getCode(), MessageStatus.SENDING.getCode(),
                MessageStatus.FAILED.getCode(), MessageStatus.CONFIRM_FAILED.getCode(), MessageStatus.RETURNED.getCode(), 5, 100);
    }

    private String onlyRequestId() {
        return jdbc.queryForObject("SELECT request_id FROM seckill_message", String.class);
    }

    private int status(String id) {
        return jdbc.queryForObject("SELECT status FROM seckill_message WHERE request_id=?", Integer.class, id);
    }

    private int independentStatus(String id) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT status FROM seckill_message WHERE request_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "local message must already be committed before publish");
                return result.getInt(1);
            }
        }
    }
}
