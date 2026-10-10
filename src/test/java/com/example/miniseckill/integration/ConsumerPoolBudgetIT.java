package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.*;
import com.example.miniseckill.dto.OrderQueryResponse;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.job.ConsumingMessageRecoveryJob;
import com.example.miniseckill.job.OrderTimeoutJob;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.mq.SeckillConsumer;
import com.example.miniseckill.service.*;
import com.example.miniseckill.service.impl.OrderServiceImpl;
import com.rabbitmq.client.Channel;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private SeckillOrderMapper orderMapper;
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
        SqlSessionTemplate session = session(dataSource);
        orderMapper = session.getMapper(SeckillOrderMapper.class);
        messages = session.getMapper(SeckillMessageMapper.class);
        logs = session.getMapper(SeckillLogMapper.class);
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        metrics = mock(SeckillMetrics.class);
        properties = new SeckillProperties();
        properties.getMysqlStockSegment().setEnabled(false);
        AtomicLong ids = new AtomicLong(1_000_000L);
        manager = new DataSourceTransactionManager(dataSource);
        OrderServiceImpl target = new OrderServiceImpl(orderMapper,
                session.getMapper(SkuStockMapper.class), logs, messages, session.getMapper(SkuStockSegmentMapper.class),
                redis, properties, metrics, ids::getAndIncrement);
        ProxyFactory orderProxy = new ProxyFactory(target);
        orderProxy.setInterfaces(OrderService.class);
        orderProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        orders = (OrderService) orderProxy.getProxy();
        consumer = listener(messages, orders);
        session.getMapper(SkuStockMapper.class).upsertStock(1L, 1001L, 10);
    }

    private SqlSessionTemplate session(DataSource source) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(SeckillOrderMapper.class, SeckillMessageMapper.class, SeckillLogMapper.class,
                SkuStockMapper.class, SkuStockSegmentMapper.class)) { configuration.addMapper(mapper); }
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        return new SqlSessionTemplate(factory.getObject());
    }

    private HikariDataSource pool(String name, int maximum) {
        return pool(name, maximum, 1);
    }

    private HikariDataSource pool(String name, int maximum, int lockWaitSeconds) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(MYSQL.getJdbcUrl());
        pool.setUsername(MYSQL.getUsername());
        pool.setPassword(MYSQL.getPassword());
        pool.setPoolName(name);
        pool.setMaximumPoolSize(maximum);
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(250);
        pool.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout=" + lockWaitSeconds);
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

    /** The old ABA needed a committed CONSUMING row that recovery could flip and a new
     * consumer could re-claim. The claim now commits only together with the outcome. */
    @Test void inFlightClaimIsInvisibleToRecoveryAndTimeoutCasWaitsThenChangesNothing() throws Exception {
        setUp(false);
        SeckillMessage message = seed("in-flight", 9L);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OrderService paused = new OrderService() {
            @Override public void createOrderFromMessage(SeckillMessage m) { orders.createOrderFromMessage(m); }
            @Override public void createOrderFromConsumingMessage(SeckillMessage m) {
                claimed.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("never released"); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                orders.createOrderFromConsumingMessage(m);
            }
            @Override public void reconcileExistingOrderFromConsumingMessage(SeckillMessage m) {
                orders.reconcileExistingOrderFromConsumingMessage(m);
            }
            @Override public void recordFailedOrder(SeckillMessage m) { orders.recordFailedOrder(m); }
            @Override public OrderQueryResponse queryOrder(Long activityId, Long userId, Long skuId) {
                return orders.queryOrder(activityId, userId, skuId);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        // Production waits up to 50s for a row lock; give the timeout job room to wait for the commit.
        HikariDataSource jobPool = pool("test-timeout-job", 1, 10);
        try {
            Channel first = mock(Channel.class);
            Future<?> inFlight = executor.submit(() -> {
                listener(messages, paused).consume(message, raw(), first); return null;
            });
            assertTrue(claimed.await(10, TimeUnit.SECONDS));

            assertEquals(MessageStatus.SENT.getCode(), status(message));
            assertTrue(messages.selectStaleConsuming(MessageStatus.CONSUMING.getCode(),
                    LocalDateTime.now().plusHours(1), 10).isEmpty());
            properties.getConsumingRecovery().setStaleTimeout(Duration.ofHours(-1));
            new ConsumingMessageRecoveryJob(messages, orderMapper, logs, redis, properties, metrics)
                    .recoverStaleConsumingMessages();

            Channel duplicate = mock(Channel.class);
            consumer.consume(message, raw(), duplicate);
            verify(duplicate).basicNack(42L, false, true);

            properties.getOrderTimeout().setQueuedTimeout(Duration.ofHours(-1));
            SeckillMessageMapper jobMessages = session(jobPool).getMapper(SeckillMessageMapper.class);
            Future<?> timeout = executor.submit(() -> {
                new OrderTimeoutJob(jobMessages, logs, mock(CompensationRecordMapper.class), redis, properties)
                        .closeTimeoutOrders();
                return null;
            });
            awaitRowLockWait();
            release.countDown();
            inFlight.get(15, TimeUnit.SECONDS);
            timeout.get(15, TimeUnit.SECONDS);

            verify(first).basicAck(42L, false);
            assertSuccess(message);
            assertEquals(0, observer.queryForObject("SELECT SUM(retry_count) FROM seckill_message", Integer.class));
            verify(redis, never()).delete(anyString());

            Channel redelivered = mock(Channel.class);
            consumer.consume(message, raw(), redelivered);
            verify(redelivered).basicAck(42L, false);
            assertSuccess(message);
            assertEquals(9, observer.queryForObject("SELECT available_stock FROM sku_stock", Integer.class));
        } finally {
            release.countDown();
            executor.shutdownNow();
            jobPool.close();
        }
    }

    private void awaitRowLockWait() throws InterruptedException {
        // innodb_trx needs PROCESS; the Testcontainers root shares the test password.
        JdbcTemplate root = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (root.queryForObject(
                "SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT'", Integer.class) == 0) {
            assertTrue(System.nanoTime() < deadline, "timeout job never waited on the in-flight claim");
            Thread.sleep(10);
        }
    }

    private int status(SeckillMessage message) {
        return observer.queryForObject("SELECT status FROM seckill_message WHERE request_id=?",
                Integer.class, message.getRequestId());
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
