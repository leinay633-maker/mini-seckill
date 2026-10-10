package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.miniseckill.common.*;
import com.example.miniseckill.config.*;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.StockFacts;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.service.*;
import com.example.miniseckill.service.impl.OrderServiceImpl;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.Duration;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

/** Real MySQL 8 + Redis 7.2.7, production SQL/Lua and Spring transactions.
 * Fixed-size races; no throughput result, no broker/process-kill simulation claim. */
@Testcontainers(disabledWithoutDocker = true)
class StockCoordinationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("mini_seckill").withUsername("miniseckill").withPassword("miniseckill");
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.2.7").withExposedPorts(6379);
    DriverManagerDataSource ds;
    JdbcTemplate jdbc;
    StringRedisTemplate redis;
    LettuceConnectionFactory connection;
    SqlSessionTemplate session;
    SeckillMessageMapper messages;
    StockFactsMapper facts;
    SkuStockMapper stocks;
    InventoryCoordinator a,b;
    SeckillProperties properties;
    CoordinationFaults faults;
    TransactionTemplate tx;
    ConsumerOrderTransactions consumer;
    final String total=RedisKeyUtil.stockKey(1L,1001L), lock=RedisKeyUtil.reconcileLockKey(1L,1001L);
    final String inflight=InventoryCoordinator.inflightKey(1L,1001L);
    @BeforeEach void setUp() throws Exception {
        ds=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        new ResourceDatabasePopulator(new FileSystemResource("sql/init.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds);
        for(String table:List.of("seckill_order","seckill_message","seckill_log","sku_stock_segment","sku_stock"))jdbc.update("DELETE FROM "+table);
        Configuration config=new Configuration();config.setMapUnderscoreToCamelCase(true);
        for(Class<?> mapper:List.of(SeckillMessageMapper.class,StockFactsMapper.class,SkuStockMapper.class,
            SkuStockSegmentMapper.class,SeckillOrderMapper.class,SeckillLogMapper.class))config.addMapper(mapper);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
        session=new SqlSessionTemplate(factory.getObject());messages=session.getMapper(SeckillMessageMapper.class);
        facts=session.getMapper(StockFactsMapper.class);stocks=session.getMapper(SkuStockMapper.class);
        connection=new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),REDIS.getMappedPort(6379)));
        connection.afterPropertiesSet();connection.start();redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
        try(var c=connection.getConnection()){c.serverCommands().flushDb();}
        properties=new SeckillProperties();properties.getStockShard().setEnabled(true);properties.getStockShard().setBucketCount(2);properties.getMysqlStockSegment().setEnabled(false);
        faults=mock(CoordinationFaults.class);a=coordinator(faults,Duration.ofSeconds(20));b=coordinator(mock(CoordinationFaults.class),Duration.ofSeconds(20));
        var manager=new DataSourceTransactionManager(ds);tx=new TransactionTemplate(manager);tx.setTimeout(15);
        AtomicLong ids=new AtomicLong(100000);
        var serviceTarget=new OrderServiceImpl(session.getMapper(SeckillOrderMapper.class),stocks,session.getMapper(SeckillLogMapper.class),messages,
            session.getMapper(SkuStockSegmentMapper.class),redis,properties,mock(SeckillMetrics.class),ids::getAndIncrement);
        ProxyFactory op=new ProxyFactory(serviceTarget);op.setInterfaces(OrderService.class);
        op.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        ProxyFactory cp=new ProxyFactory(new ConsumerOrderTransactions(messages,(OrderService)op.getProxy()));cp.setProxyTargetClass(true);
        cp.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));consumer=(ConsumerOrderTransactions)cp.getProxy();
        stocks.insertStock(1L,1001L,10);a.initializeNew(1L,1001L,10);
    }
    @AfterEach void close(){if(connection!=null)connection.destroy();}
    InventoryCoordinator coordinator(CoordinationFaults hook,Duration lease){var p=new StockCoordinationProperties();p.setRepairLease(lease);return new InventoryCoordinator(redis,messages,facts,properties,p,mock(SoldOutCacheService.class),hook,mock(SeckillMetrics.class),new RedisProperties());}
    void reserve(InventoryCoordinator c,String id,long user){redis.opsForValue().set(RedisKeyUtil.userSkuKey(1L,user,1001L),id,Duration.ofMinutes(5));assertEquals(1L,c.reserve(1L,1001L,user,id));}
    void persist(String id,long user){assertEquals(1,messages.insertPending(id,1L,user,1001L,9));a.accepted(1L,1001L,user,id);}
    long stock(){return Long.parseLong(Objects.requireNonNull(redis.opsForValue().get(total)));}
    static void await(BooleanSupplier condition,String description) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)fail(description);Thread.sleep(20);}}
    static void waitLatch(CountDownLatch latch){try{if(!latch.await(12,TimeUnit.SECONDS))throw new IllegalStateException("barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}

    @Test void completedAdmissionDuringSnapshotCannotBeOverwrittenEvenWhenLedgerIsEmpty() throws Exception {
        CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(i->{read.countDown();waitLatch(release);return null;}).when(faults).hit(eq("after-snapshot"),anyString());
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {Future<InventoryCoordinator.RepairResult> old=pool.submit(()->a.reconcile(1L,1001L));assertTrue(read.await(10,TimeUnit.SECONDS));
            reserve(b,"new",7);persist("new",7);assertEquals(0L,redis.opsForHash().size(inflight));release.countDown();
            assertEquals("VERSION_CHANGED",old.get(10,TimeUnit.SECONDS).outcome());assertEquals(9,stock());
            assertEquals(9L,facts.snapshot(1L,1001L,false).expected());assertEquals("UNCHANGED",b.reconcile(1L,1001L).outcome());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void expiredOwnerIsFencedAtRedisAndCannotDeleteSuccessorsLease() throws Exception {
        a=coordinator(faults,Duration.ofMillis(200));CountDownLatch read=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(i->{read.countDown();waitLatch(release);return null;}).when(faults).hit(eq("after-snapshot"),anyString());
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {Future<InventoryCoordinator.RepairResult> old=pool.submit(()->a.reconcile(1L,1001L));assertTrue(read.await(10,TimeUnit.SECONDS));
            await(()->!Boolean.TRUE.equals(redis.hasKey(lock)),"Redis lease must really expire");
            redis.opsForValue().set(total,"1");assertEquals("APPLIED",b.reconcile(1L,1001L).outcome());
            assertTrue(Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,"B",Duration.ofSeconds(20))));release.countDown();
            assertEquals("LEASE_LOST",old.get(10,TimeUnit.SECONDS).outcome());assertEquals("B",redis.opsForValue().get(lock));assertEquals(10,stock());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void reservationPreventsRepairBeforeDatabaseInsertionAndCancellationFencesLateInsert() {
        reserve(a,"late",7);assertEquals("INFLIGHT",b.reconcile(1L,1001L).outcome());assertEquals(9,stock());
        b.resolveUncertain(1L,1001L,7L,"late");assertEquals(11,messages.selectByRequestId("late").getStatus());assertEquals(10,stock());
        assertThrows(DuplicateKeyException.class,()->messages.insertPending("late",1L,7L,1001L,9));
        a.resolveUncertain(1L,1001L,7L,"late");assertEquals(10,stock());assertEquals(0L,redis.opsForHash().size(inflight));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void cancellationCannotPassUncommittedUniqueKeyAndUsesActualFinalOutcome(boolean commit) throws Exception {
        reserve(a,"ambiguous",7);
        CountDownLatch inserted=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService pool=Executors.newSingleThreadExecutor();
        // Ask the server to bound this resolver's actual unique-key wait. Do not infer
        // LOCK WAIT from information_schema.innodb_trx: that observer was unreliable
        // in the repository's PR #4 CI. Error 1205 proves the conflicting DB operation.
        DriverManagerDataSource bounded=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Properties options=new Properties();options.setProperty("sessionVariables","innodb_lock_wait_timeout=1");
        bounded.setConnectionProperties(options);
        Configuration config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.addMapper(SeckillMessageMapper.class);
        SqlSessionFactoryBean factory=new SqlSessionFactoryBean();factory.setDataSource(bounded);factory.setConfiguration(config);
        var boundedMessages=new SqlSessionTemplate(factory.getObject()).getMapper(SeckillMessageMapper.class);
        var resolver=new InventoryCoordinator(redis,boundedMessages,facts,properties,new StockCoordinationProperties(),
            mock(SoldOutCacheService.class),mock(CoordinationFaults.class),mock(SeckillMetrics.class),new RedisProperties());
        try {
            Future<?> original=pool.submit(()->tx.execute(status->{
                messages.insertPending("ambiguous",1L,7L,1001L,9);inserted.countDown();waitLatch(release);
                if(!commit)status.setRollbackOnly();return null;
            }));
            assertTrue(inserted.await(10,TimeUnit.SECONDS));
            RuntimeException failure=assertThrows(RuntimeException.class,()->resolver.resolveUncertain(1L,1001L,7L,"ambiguous"));
            Throwable cause=failure;boolean lockWaitTimeout=false;
            while(cause!=null){if(cause instanceof SQLException sql && sql.getErrorCode()==1205)lockWaitTimeout=true;cause=cause.getCause();}
            assertTrue(lockWaitTimeout,()->"expected actual MySQL lock wait timeout 1205: "+failure);
            assertFalse(original.isDone());assertEquals(9,stock());assertEquals(1L,redis.opsForHash().size(inflight));
            assertNull(messages.selectByRequestId("ambiguous"),"uncommitted admission is invisible, not proof of rollback");
            release.countDown();original.get(10,TimeUnit.SECONDS);
            b.resolveUncertain(1L,1001L,7L,"ambiguous");
            assertEquals(commit?9:11,messages.selectByRequestId("ambiguous").getStatus());
            assertEquals(commit?9:10,stock());assertEquals(0L,redis.opsForHash().size(inflight));
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test void concurrentResolversRefundOnlyOnceUsingRealRedisLua() throws Exception {
        reserve(a,"cancel",7);ExecutorService pool=Executors.newFixedThreadPool(8);
        try {List<Future<?>> work=new ArrayList<>();for(int i=0;i<8;i++)work.add(pool.submit(()->{b.resolveUncertain(1L,1001L,7L,"cancel");return null;}));for(Future<?> f:work)f.get(10,TimeUnit.SECONDS);
            assertEquals(10,stock());assertEquals(0L,redis.opsForHash().size(inflight));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM seckill_message",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test void sendAttemptAbaRejectsOldCallbacksAndTimeoutRechecksTheRowNotTheScan() {
        messages.insertPending("send",1L,7L,1001L,0);assertEquals(1,messages.claimSend("send","A",5));
        assertEquals(0,messages.claimSend("send","B",5));assertEquals(0,messages.timeoutIfStale("send",TimeUnit.MINUTES.toMicros(10)));
        jdbc.update("UPDATE seckill_message SET send_lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE request_id='send'");
        assertEquals(1,messages.claimSend("send","B",5));assertEquals(0,messages.finishSend("send","A",3,"late error"));
        assertEquals(1,messages.finishSend("send","B",1,null));assertEquals(0,messages.finishSend("send","A",4,"late nack"));
        assertEquals(1,messages.selectByRequestId("send").getStatus());assertEquals(2,messages.selectByRequestId("send").getRetryCount());
    }
    @Test void databaseTimeCandidateAndTimeoutCasRefuseARefreshedOrLeasedRow() {
        messages.insertPending("timeout",1L,7L,1001L,1);
        jdbc.update("UPDATE seckill_message SET updated_at=DATE_SUB(NOW(),INTERVAL 30 MINUTE) WHERE request_id='timeout'");
        long age=TimeUnit.MINUTES.toMicros(10);
        assertEquals(1,messages.selectTimeoutDue(age,10).size());
        jdbc.update("UPDATE seckill_message SET updated_at=NOW() WHERE request_id='timeout'");
        assertEquals(0,messages.timeoutIfStale("timeout",age));
        jdbc.update("UPDATE seckill_message SET updated_at=DATE_SUB(NOW(),INTERVAL 30 MINUTE), send_lease_until=DATE_ADD(NOW(6),INTERVAL 20 SECOND) WHERE request_id='timeout'");
        assertTrue(messages.selectTimeoutDue(age,10).isEmpty());
        assertEquals(0,messages.timeoutIfStale("timeout",age));
        jdbc.update("UPDATE seckill_message SET send_lease_until=NULL WHERE request_id='timeout'");
        assertEquals(1,messages.timeoutIfStale("timeout",age));
        assertEquals(6,messages.selectByRequestId("timeout").getStatus());
    }
    @Test void callbacksAfterLeaseExpiryCannotWriteEvenWithoutANewerOwner() {
        messages.insertPending("send",1L,7L,1001L,9);
        jdbc.update("UPDATE seckill_message SET send_lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE request_id='send'");
        assertEquals(0,messages.finishSend("send","send",1,null));assertEquals(9,messages.selectByRequestId("send").getStatus());
    }
    @Test void twoSchedulersCanDiscoverButOnlyOneClaimsTheSameSendingRow() throws Exception {
        messages.insertPending("send",1L,7L,1001L,0);ExecutorService pool=Executors.newFixedThreadPool(8);
        try {List<Future<Integer>> work=new ArrayList<>();for(int i=0;i<8;i++){final int id=i;work.add(pool.submit(()->messages.claimSend("send","owner"+id,5)));}
            int winners=0;for(Future<Integer> f:work)winners+=f.get(10,TimeUnit.SECONDS);assertEquals(1,winners);assertEquals(1,messages.selectByRequestId("send").getRetryCount());
        } finally {pool.shutdownNow();}
    }
    @Test void oneStatementSeesConsistentCountersAndConsumerCommitPreservesBudget() {
        reserve(a,"order",7);persist("order",7);StockFacts before=facts.snapshot(1L,1001L,false);
        assertTrue(before.consistent());assertEquals(9,before.expected());long separatelyReadSold=before.sold();
        assertTrue(consumer.create(new SeckillMessage("order",1L,7L,1001L,1L)));
        long laterReadSuccess=session.getMapper(SeckillOrderMapper.class).countByActivitySkuStatus(1L,1001L,2);
        assertNotEquals(separatelyReadSold,laterReadSuccess,"reproduces false mismatch from two independent reads");
        StockFacts after=facts.snapshot(1L,1001L,false);assertTrue(after.consistent());assertEquals(1,after.successful());assertEquals(before.expected(),after.expected());
        assertEquals("UNCHANGED",b.reconcile(1L,1001L).outcome());assertEquals(9,stock());
    }
    @Test void segmentedSnapshotUsesSegmentFactsNotLaggingMasterCounters() {
        var segments=session.getMapper(SkuStockSegmentMapper.class);segments.upsertSegment(1L,1001L,0,5);segments.upsertSegment(1L,1001L,1,5);
        StockFacts fact=facts.snapshot(1L,1001L,true);assertTrue(fact.consistent());assertEquals(10,fact.expected());
        jdbc.update("UPDATE sku_stock SET available_stock=0,sold_count=10 WHERE activity_id=1 AND sku_id=1001");
        assertTrue(facts.snapshot(1L,1001L,true).consistent());assertFalse(facts.snapshot(1L,1001L,false).consistent());
    }
    @Test void exhaustionCannotStealLiveLeaseAndNeverReopensFinalBudget() {
        messages.insertPending("send",1L,7L,1001L,9);jdbc.update("UPDATE seckill_message SET retry_count=5 WHERE request_id='send'");
        assertEquals(0,messages.exhaustSend("send",5));jdbc.update("UPDATE seckill_message SET send_lease_until=DATE_SUB(NOW(6),INTERVAL 1 SECOND) WHERE request_id='send'");
        assertEquals(1,messages.exhaustSend("send",5));assertEquals(0,messages.claimSend("send","late",5));assertEquals(0,messages.finishSend("send","send",1,null));assertEquals(10,facts.snapshot(1L,1001L,false).expected());
    }
    @Test void initializationIsInsertOnlyUnderRealDatabaseUniqueConstraint() {
        var target=new StockInitialization(stocks,session.getMapper(SkuStockSegmentMapper.class),properties);
        assertThrows(BusinessException.class,()->tx.execute(s->{target.create(1L,1001L,999);return null;}));assertEquals(10,stocks.selectBySkuId(1L,1001L).getTotalStock());assertEquals(10,stock());
    }
}
