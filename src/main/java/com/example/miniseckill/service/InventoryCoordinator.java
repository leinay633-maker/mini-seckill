package com.example.miniseckill.service;

import com.example.miniseckill.common.*;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.config.StockCoordinationProperties;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.entity.StockFacts;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.StockFactsMapper;
import com.example.miniseckill.util.RedisKeyUtil;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Redis-primary-local fencing + quiescent reservation frontier. See the proof in docs. */
@Service
public class InventoryCoordinator {
    private static final Logger log = LoggerFactory.getLogger(InventoryCoordinator.class);
    private final StringRedisTemplate redis;
    private final SeckillMessageMapper messages;
    private final StockFactsMapper facts;
    private final SeckillProperties properties;
    private final StockCoordinationProperties policy;
    private final SoldOutCacheService soldOut;
    private final CoordinationFaults faults;
    private final SeckillMetrics metrics;
    private final DefaultRedisScript<Long> reserve = script("coord_reserve", Long.class);
    private final DefaultRedisScript<Long> settle = script("coord_settle", Long.class);
    private final DefaultRedisScript<Long> initialize = script("coord_initialize", Long.class);
    private final DefaultRedisScript<Long> release = script("compare_delete", Long.class);
    private final DefaultRedisScript<Long> projection = script("coord_project", Long.class);
    private final DefaultRedisScript<List> begin = script("coord_begin", List.class);
    private final DefaultRedisScript<List> expired = script("coord_expired", List.class);
    private final DefaultRedisScript<String> repair = script("coord_repair", String.class);
    public InventoryCoordinator(StringRedisTemplate redis, SeckillMessageMapper messages, StockFactsMapper facts,
            SeckillProperties properties, StockCoordinationProperties policy, SoldOutCacheService soldOut,
            CoordinationFaults faults, SeckillMetrics metrics, RedisProperties redisProperties) {
        this.redis = redis; this.messages = messages; this.facts = facts; this.properties = properties;
        this.policy = policy; this.soldOut = soldOut; this.faults = faults; this.metrics = metrics;
        if (properties.isMqFallbackSync()) throw new IllegalArgumentException("coordinated admission requires mq-fallback-sync=false");
        if (redisProperties.getCluster() != null && redisProperties.getCluster().getNodes() != null
                && !redisProperties.getCluster().getNodes().isEmpty())
            throw new IllegalArgumentException("coordinated inventory requires one Redis primary; cross-slot Cluster is unsupported");
    }
    private static <T> DefaultRedisScript<T> script(String name, Class<T> type) {
        DefaultRedisScript<T> value = new DefaultRedisScript<>();
        value.setLocation(new ClassPathResource("lua/" + name + ".lua")); value.setResultType(type); return value;
    }
    /** Ledger release/refund must not precede a surrounding MySQL transaction's commit. */
    public static void requireAutocommitBoundary() {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("inventory coordination cannot join an ambient MySQL transaction");
    }
    private static String nonce() { return UUID.randomUUID().toString(); }
    // Layout travels with every frontier version: mismatched rolling configurations fail closed.
    private String frontier() {
        int buckets = properties.getStockShard().isEnabled() ? Math.max(1, properties.getStockShard().getBucketCount()) : 0;
        return buckets + ":" + nonce();
    }
    public static String versionKey(Long a, Long s) { return "seckill:coord:" + a + ":" + s + ":version"; }
    public static String inflightKey(Long a, Long s) { return "seckill:coord:" + a + ":" + s + ":inflight"; }
    public static String deadlinesKey(Long a, Long s) { return "seckill:coord:" + a + ":" + s + ":deadlines"; }
    private List<String> keys(Long a, Long s, Long user, boolean status) {
        List<String> result = new ArrayList<>(List.of(RedisKeyUtil.stockKey(a,s), versionKey(a,s), inflightKey(a,s),
                deadlinesKey(a,s), RedisKeyUtil.reconcileLockKey(a,s)));
        if (user != null) result.add(RedisKeyUtil.userSkuKey(a,user,s));
        if (status) result.add(RedisKeyUtil.orderStatusKey(a,user,s));
        if (properties.getStockShard().isEnabled()) {
            for (int i=0; i<Math.max(1, properties.getStockShard().getBucketCount()); i++)
                result.add(RedisKeyUtil.stockBucketKey(a,s,i));
        }
        return result;
    }
    public Long reserve(Long a, Long s, Long user, String requestId) {
        requireAutocommitBoundary();
        int count = Math.max(1, properties.getStockShard().getBucketCount());
        Long result = redis.execute(reserve, keys(a,s,user,false), requestId, user.toString(),
                Long.toString(policy.getReservationTimeout().toMillis()), frontier(),
                Integer.toString(Math.floorMod(user.hashCode(), count)));
        if (Long.valueOf(1L).equals(result)) faults.hit("after-reserve", requestId + " " + a + " " + user + " " + s);
        return result;
    }
    /** INSERT has definitely returned success. Failure here leaves a recoverable reservation, not a refund. */
    public void accepted(Long a, Long s, Long user, String requestId) {
        requireAutocommitBoundary();
        faults.hit("after-persist", requestId + " " + a + " " + user + " " + s);
        try { settleKnown(a,s,user,requestId,false); }
        catch (RuntimeException e) { log.warn("COORD_ACCEPTED_PENDING_SETTLEMENT requestId={}", requestId, e); }
    }
    /** Never infer rollback from an exception. The unique-key cancellation insertion is the database fence. */
    public void resolveUncertain(Long a, Long s, Long user, String requestId) {
        requireAutocommitBoundary();
        messages.insertAdmissionCancellation(requestId,a,user,s);
        SeckillMessageRecord row = messages.selectByRequestId(requestId);
        if (row == null || !a.equals(row.getActivityId()) || !s.equals(row.getSkuId()) || !user.equals(row.getUserId()))
            throw new IllegalStateException("reservation identity cannot be proven: " + requestId);
        boolean refund = Integer.valueOf(MessageStatus.CANCELLED.getCode()).equals(row.getStatus());
        settleKnown(a,s,user,requestId,refund);
        metric(refund ? "reservation_cancelled" : "reservation_durable");
        log.info("COORD_RESOLVED requestId={} cancelled={}", requestId, refund);
    }
    private void settleKnown(Long a, Long s, Long user, String requestId, boolean refund) {
        Object metadata = redis.opsForHash().get(inflightKey(a,s),requestId);
        if (metadata == null) return; // another resolver already completed the idempotent Lua
        String value = metadata.toString();
        if (!value.startsWith(user + ":")) throw new IllegalStateException("reservation owner mismatch");
        Long code = redis.execute(settle, keys(a,s,user,true), requestId,value,refund ? "1":"0",frontier(),
                Integer.toString(OrderStatus.FAILED.getCode()),Long.toString(properties.getOrderStatusTtl().toMillis()));
        if (code == null || code < 0) throw new IllegalStateException("reservation settlement refused: " + code);
    }
    public void recoverExpired(Long a, Long s) {
        List<?> ids = redis.execute(expired, List.of(deadlinesKey(a,s)), Integer.toString(policy.getRecoveryBatchSize()));
        if (ids == null) throw new IllegalStateException("reservation scan returned no result");
        for (Object raw : ids) {
            String id = raw.toString();
            try {
                Object metadata = redis.opsForHash().get(inflightKey(a,s), id);
                if (metadata == null) throw new IllegalStateException("deadline without reservation metadata");
                long user = Long.parseLong(metadata.toString().split(":",2)[0]);
                resolveUncertain(a,s,user,id);
            } catch (RuntimeException e) {
                metric("reservation_unknown"); log.warn("COORD_RESOLUTION_UNKNOWN requestId={}",id,e);
            }
        }
    }
    public record RepairResult(String outcome, Long expected) {
        public boolean complete() { return "APPLIED".equals(outcome) || "UNCHANGED".equals(outcome); }
    }
    public RepairResult reconcile(Long a, Long s) {
        requireAutocommitBoundary();
        String owner = nonce();
        String lock = RedisKeyUtil.reconcileLockKey(a,s);
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lock,owner,policy.getRepairLease())))
            return outcome("BUSY",null,a,s);
        try {
            List<?> gate = redis.execute(begin,keys(a,s,null,false),owner);
            if (gate == null || gate.isEmpty()) throw new IllegalStateException("empty repair gate response");
            if (!"READY".equals(gate.get(0).toString())) return outcome(gate.get(0).toString(),null,a,s);
            StockFacts row = facts.snapshot(a,s,properties.getMysqlStockSegment().isEnabled());
            if (row == null || !row.consistent()) return outcome("MYSQL_MISMATCH",null,a,s);
            faults.hit("after-snapshot",a + " " + s + " " + owner + " " + gate.get(1) + " " + row.expected());
            String result = redis.execute(repair,keys(a,s,null,false),owner,gate.get(1).toString(),
                    Long.toString(row.expected()),frontier());
            if (result == null) throw new IllegalStateException("empty repair result");
            if ("APPLIED".equals(result) || "UNCHANGED".equals(result)) {
                try { if (row.expected() > 0) soldOut.clear(a,s); else soldOut.markSoldOut(a,s); }
                catch (RuntimeException e) { log.warn("COORD_LOCAL_CACHE_FAILED activityId={} skuId={}", a,s,e); }
            }
            return outcome(result,row.expected(),a,s);
        } finally {
            // An expired owner cannot release its successor's lease. Failure is a liveness issue only.
            try { redis.execute(release,List.of(lock),owner); }
            catch (RuntimeException e) { log.warn("COORD_LEASE_RELEASE_FAILED activityId={} skuId={}",a,s,e); }
        }
    }
    private RepairResult outcome(String result, Long expected, Long a, Long s) {
        metric("repair_" + result.toLowerCase(Locale.ROOT));
        log.info("COORD_REPAIR outcome={} activityId={} skuId={} expected={} atMillis={}",
                result,a,s,expected,System.currentTimeMillis());
        return new RepairResult(result,expected);
    }
    public void initializeNew(Long a, Long s, int stock) {
        requireAutocommitBoundary();
        Long result = redis.execute(initialize,keys(a,s,null,false),Integer.toString(stock),frontier());
        if (!Long.valueOf(1).equals(result)) throw new BusinessException(409,"Redis 库存已有协调状态，拒绝重置");
        if (stock > 0) soldOut.clear(a,s); else soldOut.markSoldOut(a,s);
    }
    public void projectOwned(Long a, Long s, Long user, String requestId, OrderStatus status, boolean releaseOwner) {
        redis.execute(projection,List.of(RedisKeyUtil.userSkuKey(a,user,s),RedisKeyUtil.orderStatusKey(a,user,s)),
                requestId,Integer.toString(status.getCode()),Long.toString(properties.getOrderStatusTtl().toMillis()),
                releaseOwner ? "1" : "0");
    }
    public void releaseOwner(Long a, Long s, Long user, String requestId) {
        redis.execute(release,List.of(RedisKeyUtil.userSkuKey(a,user,s)),requestId);
    }
    private void metric(String result) { try { metrics.coordination(result); } catch (RuntimeException ignored) { } }
}
