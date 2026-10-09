package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.*;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.mq.SeckillConsumer;
import com.example.miniseckill.service.*;
import com.example.miniseckill.service.impl.OrderServiceImpl;
import com.rabbitmq.client.Channel;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Hikari + MySQL + production mappers and Spring transaction proxies.
 * One test also uses a real RabbitMQ MANUAL listener with prefetch=1.
 * Redis/metrics are doubles. These bounded fault tests are not performance data. */
@Testcontainers(disabledWithoutDocker = true)
class ConsumerPoolBudgetIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mini_seckill").withUsername("miniseckill").withPassword("miniseckill");
    @Container static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:3.13-management")
            .withExposedPorts(5672);

    private HikariDataSource admission;
    private HikariDataSource reserved;
    private DataSource dataSource;
    private JdbcTemplate observer;
    private SeckillMessageMapper messages;
    private SeckillLogMapper logs;
    private OrderService orders;
    private DataSourceTransactionManager manager;
    private StringRedisTemplate redis;
    private SeckillMetrics metrics;
    private SeckillProperties properties;
    private SeckillConsumer consumer;

    @SuppressWarnings("unchecked")
    private void setUp(boolean isolated) throws Exception {
        DriverManagerDataSource outside = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        new ResourceDatabasePopulator(new FileSystemResource("sql/init.sql")).execute(outside);
        observer = new JdbcTemplate(outside);
        for (String table : List.of("seckill_order", "seckill_message", "seckill_log", "sku_stock_segment", "sku_stock")) {
            observer.update("DELETE FROM " + table);
        }
        admission = pool("test-admission", isolated ? 1 : 2);
        if (isolated) {
            reserved = pool("test-consumer", 1);
            dataSource = new PoolBudgetConfiguration(new PoolBudgetProperties(),
                    new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties(), new MockEnvironment())
                    .dataSource(admission, reserved);
        } else { dataSource = admission; }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(SeckillOrderMapper.class, SeckillMessageMapper.class, SeckillLogMapper.class,
                SkuStockMapper.class, SkuStockSegmentMapper.class)) { configuration.addMapper(mapper); }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        messages = session.getMapper(SeckillMessageMapper.class);
        logs = session.getMapper(SeckillLogMapper.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        metrics = mock(SeckillMetrics.class);
        properties = new SeckillProperties();
        properties.getMysqlStockSegment().setEnabled(false);
        AtomicLong ids = new AtomicLong(1_000_000L);
        manager = new DataSourceTransactionManager(dataSource);
        OrderServiceImpl target = new OrderServiceImpl(session.getMapper(SeckillOrderMapper.class),
                session.getMapper(SkuStockMapper.class), logs, messages, session.getMapper(SkuStockSegmentMapper.class),
                redis, properties, metrics, ids::getAndIncrement);
        ProxyFactory orderProxy = new ProxyFactory(target);
        orderProxy.setInterfaces(OrderService.class);
        orderProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        orders = (OrderService) orderProxy.getProxy();
        consumer = listener(messages, orders);
        session.getMapper(SkuStockMapper.class).upsertStock(1L, 1001L, 10);
    }

    private HikariDataSource pool(String name, int maximum) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(MYSQL.getJdbcUrl());
        pool.setUsername(MYSQL.getUsername());
        pool.setPassword(MYSQL.getPassword());
        pool.setPoolName(name);
        pool.setMaximumPoolSize(maximum);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(250);
        pool.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=1");
        return pool;
    }

    private SeckillConsumer listener(SeckillMessageMapper mapper, OrderService orderService) {
        ProxyFactory proxy = new ProxyFactory(new ConsumerOrderTransactions(mapper, orderService));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        ConsumerExecutionProperties execution = new ConsumerExecutionProperties();
        execution.setRetryBackoff(Duration.ofMillis(20));
        return new SeckillConsumer((ConsumerOrderTransactions) proxy.getProxy(), logs,
                mock(CompensationRecordMapper.class), redis, properties, execution, metrics);
    }

    @AfterEach void closePools() {
        if (admission != null) { admission.close(); }
        if (reserved != null) { reserved.close(); }
        assertFalse(ConsumerPoolContext.isConsumer());
    }

    @Test void realPoolExhaustionBeforeTransactionDoesNotDeadLetterAndRecovers() throws Exception {
        setUp(false);
        SeckillMessage message = seed("pool-empty", 1L);
        Channel channel = mock(Channel.class);
        try (Connection one = admission.getConnection(); Connection two = admission.getConnection()) {
            consumer.consume(message, raw(), channel);
            verify(channel).basicNack(42L, false, true);
            verify(channel, never()).basicAck(anyLong(), anyBoolean());
            assertUnchanged(message);
        }
        consumer.consume(message, raw(), channel);
        verify(channel).basicAck(42L, false);
        assertSuccess(message);
        assertEquals(0, admission.getHikariPoolMXBean().getActiveConnections());
    }

    @Test void realClaimSqlLockTimeoutRollsBackAndRedelivers() throws Exception {
        setUp(false);
        SeckillMessage message = seed("claim-lock", 2L);
        Channel channel = mock(Channel.class);
        try (Connection blocker = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("UPDATE seckill_message SET last_error='locked' WHERE request_id=?")) {
                statement.setString(1, message.getRequestId()); statement.executeUpdate();
            }
            consumer.consume(message, raw(), channel);
            verify(channel).basicNack(42L, false, true);
            blocker.rollback();
        }
        assertUnchanged(message);
        consumer.consume(message, raw(), channel);
        assertSuccess(message);
    }

    @Test void exceptionAfterClaimRollsBackClaimInsteadOfLeavingConsuming() throws Exception {
        setUp(false);
        SeckillMessage message = seed("after-claim", 3L);
        OrderService failing = mock(OrderService.class);
        doThrow(new TransientDataAccessResourceException("injected after claim"))
                .when(failing).createOrderFromConsumingMessage(message);
        Channel channel = mock(Channel.class);
        listener(messages, failing).consume(message, raw(), channel);
        verify(channel).basicNack(42L, false, true);
        assertUnchanged(message);
        consumer.consume(message, raw(), channel);
        assertSuccess(message);
    }

    @ParameterizedTest @ValueSource(strings = {"reconcile", "failStock", "markDead"})
    void handlerFailureRollsBackItsNewClaimAndCanBeRedelivered(String stage) throws Exception {
        setUp(false);
        SeckillMessage message = seed("handler-" + stage, 4L);
        OrderService failing = mock(OrderService.class);
        SeckillMessageMapper mapper = spy(messages);
        RuntimeException failure = new TransientDataAccessResourceException("injected handler persistence failure");
        if (stage.equals("reconcile")) {
            doThrow(new DuplicateKeyException("duplicate")).when(failing).createOrderFromConsumingMessage(message);
            doThrow(failure).when(failing).reconcileExistingOrderFromConsumingMessage(message);
        } else if (stage.equals("failStock")) {
            doThrow(new InsufficientStockException("empty")).when(failing).createOrderFromConsumingMessage(message);
            doThrow(failure).when(failing).recordFailedOrder(message);
        } else {
            doThrow(new IllegalArgumentException("fatal")).when(failing).createOrderFromConsumingMessage(message);
            doThrow(failure).when(mapper).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        }
        Channel channel = mock(Channel.class);
        listener(mapper, failing).consume(message, raw(), channel);
        verify(channel).basicNack(42L, false, true);
        assertUnchanged(message);
        consumer.consume(message, raw(), channel);
        assertSuccess(message);
    }

    @Test void exhaustedAdmissionPoolCannotStarveConsumerAndReverseIsAlsoIsolated() throws Exception {
        setUp(true);
        SeckillMessage message = seed("isolation", 5L);
        try (Connection held = admission.getConnection()) {
            assertThrows(SQLTransientConnectionException.class, () -> dataSource.getConnection());
            consumer.consume(message, raw(), mock(Channel.class));
            assertSuccess(message);
        }
        try (Connection held = reserved.getConnection(); Connection ingress = dataSource.getConnection()) {
            assertTrue(ingress.isValid(1));
        }
        assertEquals(2, admission.getMaximumPoolSize() + reserved.getMaximumPoolSize());
    }

    @Test void concurrentDuplicateDeliveriesCommitOnlyOneOrder() throws Exception {
        setUp(true);
        SeckillMessage message = seed("concurrent", 6L);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(executor.submit(() -> {
                    start.await(); consumer.consume(message, raw(), mock(Channel.class)); return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) { future.get(10, TimeUnit.SECONDS); }
            assertSuccess(message);
            assertEquals(9, observer.queryForObject("SELECT available_stock FROM sku_stock", Integer.class));
        } finally { executor.shutdownNow(); }
    }

    @Test void successCommitsBeforeAckAndAckLossReplaysWithoutSecondOrder() throws Exception {
        setUp(false);
        SeckillMessage message = seed("ack-loss", 7L);
        Channel broken = mock(Channel.class);
        doAnswer(invocation -> { assertSuccess(message); throw new java.io.IOException("injected ack loss"); })
                .when(broken).basicAck(42L, false);
        assertThrows(java.io.IOException.class, () -> consumer.consume(message, raw(), broken));
        verify(broken).abort();
        Channel replay = mock(Channel.class);
        consumer.consume(message, raw(), replay);
        verify(replay).basicAck(42L, false);
        assertSuccess(message);
        assertEquals(9, observer.queryForObject("SELECT available_stock FROM sku_stock", Integer.class));
    }

    @Test void realManualListenerWithPrefetchOneKeepsRetryingThenDrainsAfterPoolRecovery() throws Exception {
        setUp(false);
        SeckillMessage message = seed("real-rabbit", 8L);
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
        connectionFactory.setUsername("guest"); connectionFactory.setPassword("guest");
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        String queue = "pool-budget-it-" + UUID.randomUUID();
        CountDownLatch retried = new CountDownLatch(2);
        Set<Integer> channels = ConcurrentHashMap.newKeySet();
        try (var adminConnection = connectionFactory.createConnection(); Channel admin = adminConnection.createChannel(false)) {
            admin.queueDeclare(queue, false, false, false, null);
            container.setQueueNames(queue);
            container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            container.setConcurrentConsumers(1); container.setPrefetchCount(1);
            container.setMessageListener((ChannelAwareMessageListener) (raw, channel) -> {
                channels.add(channel.getChannelNumber());
                consumer.consume(message, raw, channel);
                retried.countDown();
            });
            try (Connection one = admission.getConnection(); Connection two = admission.getConnection()) {
                container.start();
                admin.basicPublish("", queue, null, new byte[] {1});
                assertTrue(retried.await(15, TimeUnit.SECONDS), "MANUAL prefetch=1 must not pin the first delivery");
                assertUnchanged(message);
                assertEquals(1, channels.size(), "retry should not need channel recreation");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (observer.queryForObject("SELECT COUNT(*) FROM seckill_order", Integer.class) == 0
                    && System.nanoTime() < deadline) { Thread.sleep(20); }
            assertSuccess(message);
            container.stop();
            assertNull(admin.basicGet(queue, false), "no ready or requeued unacked delivery after graceful stop");
            admin.queueDelete(queue);
        } finally {
            container.stop(); container.destroy(); connectionFactory.destroy();
        }
    }

    private SeckillMessage seed(String request, long user) {
        messages.insertPending(request, 1L, user, 1001L, MessageStatus.SENT.getCode());
        return new SeckillMessage(request, 1L, user, 1001L, 1L);
    }
    private void assertUnchanged(SeckillMessage message) {
        assertEquals(MessageStatus.SENT.getCode(), observer.queryForObject(
                "SELECT status FROM seckill_message WHERE request_id=?", Integer.class, message.getRequestId()));
        assertEquals(0, observer.queryForObject("SELECT COUNT(*) FROM seckill_order", Integer.class));
        assertEquals(0, observer.queryForObject("SELECT SUM(retry_count) FROM seckill_message", Integer.class));
    }
    private void assertSuccess(SeckillMessage message) {
        assertEquals(MessageStatus.CONSUMED.getCode(), observer.queryForObject(
                "SELECT status FROM seckill_message WHERE request_id=?", Integer.class, message.getRequestId()));
        assertEquals(1, observer.queryForObject("SELECT COUNT(*) FROM seckill_order WHERE status=2", Integer.class));
        assertEquals(0, observer.queryForObject("SELECT COUNT(*) FROM seckill_message WHERE status IN (6,7)", Integer.class));
    }
    private Message raw() {
        MessageProperties properties = new MessageProperties(); properties.setDeliveryTag(42L);
        return new Message(new byte[0], properties);
    }
}
