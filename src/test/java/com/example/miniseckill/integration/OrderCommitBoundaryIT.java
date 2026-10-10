package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.service.impl.OrderServiceImpl;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real MySQL/InnoDB, production DDL and mappers, actual Spring transaction proxy.
 * Redis and metrics are fault-injection doubles; RabbitMQ is NOT exercised here.
 * Fixed-size races check invariants, not capacity or latency.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderCommitBoundaryIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mini_seckill")
            .withUsername("miniseckill")
            .withPassword("miniseckill");

    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private SeckillMessageMapper messages;
    private SkuStockMapper stocks;
    private OrderService service;
    private TransactionTemplate transaction;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private SeckillMetrics metrics;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(new FileSystemResource("sql/init.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        // Only this disposable Testcontainers database is touched.
        for (String table : List.of("seckill_order", "seckill_message", "seckill_log", "sku_stock_segment", "sku_stock")) {
            jdbc.update("DELETE FROM " + table);
        }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SeckillOrderMapper.class);
        configuration.addMapper(SeckillMessageMapper.class);
        configuration.addMapper(SeckillLogMapper.class);
        configuration.addMapper(SkuStockMapper.class);
        configuration.addMapper(SkuStockSegmentMapper.class);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(SeckillMessageMapper.class);
        stocks = session.getMapper(SkuStockMapper.class);
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        metrics = mock(SeckillMetrics.class);
        when(redis.opsForValue()).thenReturn(values);
        SeckillProperties properties = new SeckillProperties();
        properties.getMysqlStockSegment().setEnabled(false);
        AtomicLong ids = new AtomicLong(1_000_000L);
        OrderServiceImpl target = new OrderServiceImpl(session.getMapper(SeckillOrderMapper.class), stocks,
                session.getMapper(SeckillLogMapper.class), messages, session.getMapper(SkuStockSegmentMapper.class),
                redis, properties, metrics, ids::getAndIncrement);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        transaction = new TransactionTemplate(manager);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.setInterfaces(OrderService.class);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        service = (OrderService) proxy.getProxy();
    }

    @Test
    void successIsVisibleToIndependentConnectionBeforeRedisProjection() {
        stocks.upsertStock(1L, 1001L, 2);
        SeckillMessage message = seed("commit", 10L, MessageStatus.CONSUMING);
        doAnswer(invocation -> {
            // A separate connection cannot see an uncommitted transaction's writes.
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM seckill_order")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                try (ResultSet result = statement.executeQuery("SELECT status FROM seckill_message WHERE request_id='commit'")) {
                    assertTrue(result.next());
                    assertEquals(MessageStatus.CONSUMED.getCode(), result.getInt(1));
                }
            }
            return null;
        }).when(values).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
        service.createOrderFromConsumingMessage(message);
        assertEquals(1, available());
        verify(values).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
        verify(metrics).order("success");
    }

    @Test
    void unavailableRedisCannotRollBackCommittedMysqlOrder() {
        stocks.upsertStock(1L, 1001L, 2);
        SeckillMessage message = seed("redis-down", 10L, MessageStatus.CONSUMING);
        doThrow(new IllegalStateException("injected Redis projection failure"))
                .when(values).set(any(), any(), any(Duration.class));
        assertDoesNotThrow(() -> service.createOrderFromConsumingMessage(message));
        assertEquals(1, orderCount());
        assertEquals(1, available());
        assertEquals(MessageStatus.CONSUMED.getCode(), messageStatus("redis-down"));
        verify(metrics).order("status_cache_write_failed");
        verify(metrics).order("success");
    }

    @Test
    void rollbackAfterServiceBodyLeavesNeitherOrderNorSuccessProjection() {
        stocks.upsertStock(1L, 1001L, 2);
        SeckillMessage message = seed("rollback", 10L, MessageStatus.CONSUMING);
        transaction.executeWithoutResult(status -> {
            service.createOrderFromConsumingMessage(message);
            status.setRollbackOnly();
        });
        assertEquals(0, orderCount());
        assertEquals(2, available());
        assertEquals(MessageStatus.CONSUMING.getCode(), messageStatus("rollback"));
        verify(values, never()).set(any(), any(), any(Duration.class));
        verify(metrics, never()).order("success");
    }

    @Test
    void stockRejectionRollsBackThenPersistsFailureAndTerminalMessageAtomically() {
        stocks.upsertStock(1L, 1001L, 0);
        SeckillMessage message = seed("sold-out", 10L, MessageStatus.CONSUMING);
        assertThrows(InsufficientStockException.class, () -> service.createOrderFromConsumingMessage(message));
        assertEquals(0, orderCount());
        assertEquals(MessageStatus.CONSUMING.getCode(), messageStatus("sold-out"));
        service.recordFailedOrder(message);
        assertEquals(1, orderCount());
        assertEquals(OrderStatus.FAILED.getCode(), jdbc.queryForObject("SELECT status FROM seckill_order", Integer.class));
        assertEquals(MessageStatus.DEAD.getCode(), messageStatus("sold-out"));
        assertTrue(retryable().isEmpty());
        assertEquals(0, available());
        verify(values, never()).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
    }

    @Test
    void failedCompletionCasMissRollsBackFailureOrder() {
        SeckillMessage message = seed("closed", 10L, MessageStatus.TIMEOUT);
        assertThrows(IllegalStateException.class, () -> service.recordFailedOrder(message));
        assertEquals(0, orderCount());
        assertEquals(MessageStatus.TIMEOUT.getCode(), messageStatus("closed"));
        verify(values, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void duplicateFailedOrderNeverChangesToSuccessOnReplay() {
        SeckillMessage original = seed("failed", 10L, MessageStatus.CONSUMING);
        service.recordFailedOrder(original);
        SeckillMessage replay = seed("failed-replay", 10L, MessageStatus.CONSUMING);
        assertThrows(DuplicateKeyException.class, () -> service.createOrderFromConsumingMessage(replay));
        service.reconcileExistingOrderFromConsumingMessage(replay);
        assertEquals(1, orderCount());
        assertEquals(OrderStatus.FAILED.getCode(), jdbc.queryForObject("SELECT status FROM seckill_order", Integer.class));
        assertEquals(MessageStatus.DEAD.getCode(), messageStatus("failed-replay"));
        verify(values, never()).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
    }

    @Test
    void duplicateOrderIdWithoutMatchingBusinessKeyCannotClaimSuccess() {
        jdbc.update("INSERT INTO seckill_order(order_id,activity_id,user_id,sku_id,status,created_at,updated_at) "
                + "VALUES(1000000,1,999,1001,?,NOW(),NOW())", OrderStatus.SUCCESS.getCode());
        SeckillMessage message = seed("id-collision", 10L, MessageStatus.CONSUMING);
        assertThrows(DuplicateKeyException.class, () -> service.createOrderFromConsumingMessage(message));
        assertThrows(IllegalStateException.class, () -> service.reconcileExistingOrderFromConsumingMessage(message));
        assertEquals(MessageStatus.CONSUMING.getCode(), messageStatus("id-collision"));
        assertEquals(1, orderCount());
        verify(values, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void delayedRetrySnapshotCannotResurrectCommittedMessage() {
        stocks.upsertStock(1L, 1001L, 2);
        SeckillMessage message = seed("stale-scan", 10L, MessageStatus.SENDING);
        assertTrue(retryable().isEmpty());
        expireSendLease("stale-scan");
        assertEquals(1, retryable().size());
        assertEquals(1, messages.markConsuming("stale-scan", 10, 1, 9, 8));
        service.createOrderFromConsumingMessage(message);
        // This is the deterministic schedule: SELECT -> consumer commit -> old retry callback.
        assertEquals(0, messages.markFailedForRetry("stale-scan", 3, "late failure", LocalDateTime.now()));
        assertEquals(0, messages.markDead("stale-scan", 7, 2, "late exhaustion"));
        assertEquals(MessageStatus.CONSUMED.getCode(), messageStatus("stale-scan"));
        assertEquals(1, orderCount());
        assertEquals(1, available());
    }

    @ParameterizedTest
    @EnumSource(value = MessageStatus.class, names = {"CONSUMED", "TIMEOUT", "DEAD", "CONSUMING"})
    void sendSideWritersCannotOverwriteProtectedStates(MessageStatus status) {
        seed("protected", 10L, status);
        assertEquals(0, messages.markFailed("protected", 3, "late send"));
        assertEquals(0, messages.markFailedForRetry("protected", 3, "late retry", LocalDateTime.now()));
        assertEquals(0, messages.markDead("protected", 7, 2, "late exhaustion"));
        assertEquals(0, messages.markSending("protected", 9, 2, 6, 7, 10));
        assertEquals(0, messages.updateStatus("protected", 2));
        assertEquals(status.getCode(), messageStatus("protected"));
        assertEquals(0, messages.selectByRequestId("protected").getRetryCount());
    }

    @ParameterizedTest
    @EnumSource(value = MessageStatus.class, names = {"PENDING", "FAILED", "CONFIRM_FAILED", "RETURNED", "SENDING"})
    void eligibleSendFailuresStillAdvanceRetryBudget(MessageStatus status) {
        seed("retryable", 10L, status);
        if (status == MessageStatus.SENDING) { expireSendLease("retryable"); }
        assertEquals(1, messages.markFailedForRetry("retryable", 3, "send failed", LocalDateTime.now().minusSeconds(1)));
        assertEquals(1, messages.selectByRequestId("retryable").getRetryCount());
        assertEquals(1, retryable().size());
    }

    @Test
    void synchronousFallbackCannotCommitOrderOverTerminalMessage() {
        stocks.upsertStock(1L, 1001L, 2);
        SeckillMessage message = seed("sync-closed", 10L, MessageStatus.TIMEOUT);
        assertThrows(IllegalStateException.class, () -> service.createOrderFromMessage(message));
        assertEquals(0, orderCount());
        assertEquals(2, available());
        assertEquals(MessageStatus.TIMEOUT.getCode(), messageStatus("sync-closed"));
        verify(values, never()).set(any(), any(), any(Duration.class));
    }

    @Test
    void concurrentDuplicateDeliveriesCreateOnlyOneBusinessOrder() throws Exception {
        stocks.upsertStock(1L, 1001L, 3);
        List<SeckillMessage> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            work.add(seed("duplicate-" + i, 10L, MessageStatus.CONSUMING));
        }
        race(work);
        assertEquals(1, orderCount());
        assertEquals(2, available());
        assertEquals(1, jdbc.queryForObject("SELECT sold_count FROM sku_stock", Integer.class));
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_message WHERE status = ?",
                Integer.class, MessageStatus.CONSUMED.getCode()));
    }

    @Test
    void concurrentDistinctBuyersPreserveStockAndDurableTerminalOutcomes() throws Exception {
        stocks.upsertStock(1L, 1001L, 3);
        List<SeckillMessage> work = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            work.add(seed("buyer-" + i, 100L + i, MessageStatus.CONSUMING));
        }
        race(work);
        assertEquals(12, orderCount());
        assertEquals(0, available());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE status = ?",
                Integer.class, OrderStatus.SUCCESS.getCode()));
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE status = ?",
                Integer.class, OrderStatus.FAILED.getCode()));
        assertEquals(3, jdbc.queryForObject("SELECT sold_count FROM sku_stock", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_message WHERE status = ?",
                Integer.class, MessageStatus.CONSUMED.getCode()));
        assertEquals(9, jdbc.queryForObject("SELECT COUNT(*) FROM seckill_message WHERE status = ?",
                Integer.class, MessageStatus.DEAD.getCode()));
        assertTrue(retryable().isEmpty());
    }

    private void race(List<SeckillMessage> work) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (SeckillMessage message : work) {
                futures.add(executor.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("start barrier timed out");
                    }
                    try {
                        service.createOrderFromConsumingMessage(message);
                    } catch (DuplicateKeyException ex) {
                        service.reconcileExistingOrderFromConsumingMessage(message);
                    } catch (InsufficientStockException ex) {
                        service.recordFailedOrder(message);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private SeckillMessage seed(String requestId, long userId, MessageStatus status) {
        messages.insertPending(requestId, 1L, userId, 1001L, status.getCode());
        return new SeckillMessage(requestId, 1L, userId, 1001L, 0L);
    }

    private List<?> retryable() {
        return messages.selectRetryable(0, 9, 3, 4, 5, 8, 100);
    }

    // An initial SENDING INSERT carries its sender's lease; the retry scan only sees the row
    // after that sender is presumed dead, which these schedules assume already happened.
    private void expireSendLease(String requestId) {
        jdbc.update("UPDATE seckill_message SET send_lease_until = DATE_SUB(NOW(6), INTERVAL 1 SECOND) "
                + "WHERE request_id = ?", requestId);
    }

    private int messageStatus(String requestId) {
        return messages.selectByRequestId(requestId).getStatus();
    }

    private int orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM seckill_order", Integer.class);
    }

    private int available() {
        return stocks.selectBySkuId(1L, 1001L).getAvailableStock();
    }
}
