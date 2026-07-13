package com.example.miniseckill.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime switches and TTL values for the seckill flow.
 */
@ConfigurationProperties(prefix = "seckill")
public class SeckillProperties {

    private Duration idempotentTtl = Duration.ofMinutes(30);
    private Duration orderStatusTtl = Duration.ofHours(2);
    private Long defaultActivityId = 1L;
    private boolean mqFallbackSync = false;
    private UserAuth userAuth = new UserAuth();
    private RateLimit rateLimit = new RateLimit();
    private AntiBrush antiBrush = new AntiBrush();
    private StockShard stockShard = new StockShard();
    private MysqlStockSegment mysqlStockSegment = new MysqlStockSegment();
    private SoldOutLocalCache soldOutLocalCache = new SoldOutLocalCache();
    private DynamicRateLimit dynamicRateLimit = new DynamicRateLimit();
    private MqConsumer mqConsumer = new MqConsumer();
    private MessageRetry messageRetry = new MessageRetry();
    private Reconcile reconcile = new Reconcile();
    private OrderTimeout orderTimeout = new OrderTimeout();
    private ConsumingRecovery consumingRecovery = new ConsumingRecovery();
    private RedisRecovery redisRecovery = new RedisRecovery();
    private AdminAuth adminAuth = new AdminAuth();
    private Snowflake snowflake = new Snowflake();
    private AsyncLog asyncLog = new AsyncLog();

    public Duration getIdempotentTtl() {
        return idempotentTtl;
    }

    public void setIdempotentTtl(Duration idempotentTtl) {
        this.idempotentTtl = idempotentTtl;
    }

    public Duration getOrderStatusTtl() {
        return orderStatusTtl;
    }

    public void setOrderStatusTtl(Duration orderStatusTtl) {
        this.orderStatusTtl = orderStatusTtl;
    }

    public Long getDefaultActivityId() {
        return defaultActivityId;
    }

    public void setDefaultActivityId(Long defaultActivityId) {
        this.defaultActivityId = defaultActivityId;
    }

    public boolean isMqFallbackSync() {
        return mqFallbackSync;
    }

    public void setMqFallbackSync(boolean mqFallbackSync) {
        this.mqFallbackSync = mqFallbackSync;
    }

    public UserAuth getUserAuth() {
        return userAuth;
    }

    public void setUserAuth(UserAuth userAuth) {
        this.userAuth = userAuth;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public AntiBrush getAntiBrush() {
        return antiBrush;
    }

    public void setAntiBrush(AntiBrush antiBrush) {
        this.antiBrush = antiBrush;
    }

    public StockShard getStockShard() {
        return stockShard;
    }

    public void setStockShard(StockShard stockShard) {
        this.stockShard = stockShard;
    }

    public MysqlStockSegment getMysqlStockSegment() {
        return mysqlStockSegment;
    }

    public void setMysqlStockSegment(MysqlStockSegment mysqlStockSegment) {
        this.mysqlStockSegment = mysqlStockSegment;
    }

    public SoldOutLocalCache getSoldOutLocalCache() {
        return soldOutLocalCache;
    }

    public void setSoldOutLocalCache(SoldOutLocalCache soldOutLocalCache) {
        this.soldOutLocalCache = soldOutLocalCache;
    }

    public DynamicRateLimit getDynamicRateLimit() {
        return dynamicRateLimit;
    }

    public void setDynamicRateLimit(DynamicRateLimit dynamicRateLimit) {
        this.dynamicRateLimit = dynamicRateLimit;
    }

    public MqConsumer getMqConsumer() {
        return mqConsumer;
    }

    public void setMqConsumer(MqConsumer mqConsumer) {
        this.mqConsumer = mqConsumer;
    }

    public MessageRetry getMessageRetry() {
        return messageRetry;
    }

    public void setMessageRetry(MessageRetry messageRetry) {
        this.messageRetry = messageRetry;
    }

    public Reconcile getReconcile() {
        return reconcile;
    }

    public void setReconcile(Reconcile reconcile) {
        this.reconcile = reconcile;
    }

    public OrderTimeout getOrderTimeout() {
        return orderTimeout;
    }

    public void setOrderTimeout(OrderTimeout orderTimeout) {
        this.orderTimeout = orderTimeout;
    }

    public ConsumingRecovery getConsumingRecovery() {
        return consumingRecovery;
    }

    public void setConsumingRecovery(ConsumingRecovery consumingRecovery) {
        this.consumingRecovery = consumingRecovery;
    }

    public RedisRecovery getRedisRecovery() {
        return redisRecovery;
    }

    public void setRedisRecovery(RedisRecovery redisRecovery) {
        this.redisRecovery = redisRecovery;
    }

    public AdminAuth getAdminAuth() {
        return adminAuth;
    }

    public void setAdminAuth(AdminAuth adminAuth) {
        this.adminAuth = adminAuth;
    }

    public Snowflake getSnowflake() {
        return snowflake;
    }

    public void setSnowflake(Snowflake snowflake) {
        this.snowflake = snowflake;
    }

    public AsyncLog getAsyncLog() {
        return asyncLog;
    }

    public void setAsyncLog(AsyncLog asyncLog) {
        this.asyncLog = asyncLog;
    }

    public static class RateLimit {
        private boolean enabled = true;
        private Duration window = Duration.ofSeconds(1);
        private int skuLimit = 300;
        private int userLimit = 5;
        private int ipLimit = 80;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public int getSkuLimit() {
            return skuLimit;
        }

        public void setSkuLimit(int skuLimit) {
            this.skuLimit = skuLimit;
        }

        public int getUserLimit() {
            return userLimit;
        }

        public void setUserLimit(int userLimit) {
            this.userLimit = userLimit;
        }

        public int getIpLimit() {
            return ipLimit;
        }

        public void setIpLimit(int ipLimit) {
            this.ipLimit = ipLimit;
        }
    }

    public static class AntiBrush {
        private boolean enabled = true;
        private Duration tokenTtl = Duration.ofMinutes(2);
        private boolean tokenQuotaEnabled = true;
        private int tokenQuotaMultiplier = 3;
        private boolean hiddenPathEnabled = true;
        private boolean captchaEnabled = false;
        private Duration captchaTtl = Duration.ofMinutes(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }

        public boolean isTokenQuotaEnabled() {
            return tokenQuotaEnabled;
        }

        public void setTokenQuotaEnabled(boolean tokenQuotaEnabled) {
            this.tokenQuotaEnabled = tokenQuotaEnabled;
        }

        public int getTokenQuotaMultiplier() {
            return tokenQuotaMultiplier;
        }

        public void setTokenQuotaMultiplier(int tokenQuotaMultiplier) {
            this.tokenQuotaMultiplier = tokenQuotaMultiplier;
        }

        public boolean isHiddenPathEnabled() {
            return hiddenPathEnabled;
        }

        public void setHiddenPathEnabled(boolean hiddenPathEnabled) {
            this.hiddenPathEnabled = hiddenPathEnabled;
        }

        public boolean isCaptchaEnabled() {
            return captchaEnabled;
        }

        public void setCaptchaEnabled(boolean captchaEnabled) {
            this.captchaEnabled = captchaEnabled;
        }

        public Duration getCaptchaTtl() {
            return captchaTtl;
        }

        public void setCaptchaTtl(Duration captchaTtl) {
            this.captchaTtl = captchaTtl;
        }
    }

    public static class UserAuth {
        private boolean enabled = false;
        private String jwtSecret = "mini-seckill-demo-jwt-secret-change-me-32-bytes";
        private Duration tokenTtl = Duration.ofMinutes(30);
        private String demoUsername = "demo";
        private String demoPassword = "demo123456";
        private Long demoUserId = 10001L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getJwtSecret() {
            return jwtSecret;
        }

        public void setJwtSecret(String jwtSecret) {
            this.jwtSecret = jwtSecret;
        }

        public Duration getTokenTtl() {
            return tokenTtl;
        }

        public void setTokenTtl(Duration tokenTtl) {
            this.tokenTtl = tokenTtl;
        }

        public String getDemoUsername() {
            return demoUsername;
        }

        public void setDemoUsername(String demoUsername) {
            this.demoUsername = demoUsername;
        }

        public String getDemoPassword() {
            return demoPassword;
        }

        public void setDemoPassword(String demoPassword) {
            this.demoPassword = demoPassword;
        }

        public Long getDemoUserId() {
            return demoUserId;
        }

        public void setDemoUserId(Long demoUserId) {
            this.demoUserId = demoUserId;
        }
    }

    public static class StockShard {
        private boolean enabled = true;
        private int bucketCount = 64;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getBucketCount() {
            return bucketCount;
        }

        public void setBucketCount(int bucketCount) {
            this.bucketCount = bucketCount;
        }
    }

    public static class MysqlStockSegment {
        private boolean enabled = true;
        private int segmentCount = 32;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSegmentCount() {
            return segmentCount;
        }

        public void setSegmentCount(int segmentCount) {
            this.segmentCount = segmentCount;
        }
    }

    public static class SoldOutLocalCache {
        private boolean enabled = true;
        private Duration ttl = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }
    }

    public static class DynamicRateLimit {
        private boolean enabled = true;
        private Duration cacheTtl = Duration.ofSeconds(5);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getCacheTtl() {
            return cacheTtl;
        }

        public void setCacheTtl(Duration cacheTtl) {
            this.cacheTtl = cacheTtl;
        }
    }

    public static class MqConsumer {
        private int concurrentConsumers = 2;
        private int maxConcurrentConsumers = 8;
        private int prefetchCount = 50;

        public int getConcurrentConsumers() {
            return concurrentConsumers;
        }

        public void setConcurrentConsumers(int concurrentConsumers) {
            this.concurrentConsumers = concurrentConsumers;
        }

        public int getMaxConcurrentConsumers() {
            return maxConcurrentConsumers;
        }

        public void setMaxConcurrentConsumers(int maxConcurrentConsumers) {
            this.maxConcurrentConsumers = maxConcurrentConsumers;
        }

        public int getPrefetchCount() {
            return prefetchCount;
        }

        public void setPrefetchCount(int prefetchCount) {
            this.prefetchCount = prefetchCount;
        }
    }

    public static class MessageRetry {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(15);
        private int maxRetry = 5;
        private int batchSize = 50;
        private Duration initialBackoff = Duration.ofSeconds(5);
        private Duration maxBackoff = Duration.ofMinutes(2);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public int getMaxRetry() {
            return maxRetry;
        }

        public void setMaxRetry(int maxRetry) {
            this.maxRetry = maxRetry;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getInitialBackoff() {
            return initialBackoff;
        }

        public void setInitialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }
    }

    public static class Reconcile {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(60);
        private int scanLimit = 100;
        private int pageSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public int getScanLimit() {
            return scanLimit;
        }

        public void setScanLimit(int scanLimit) {
            this.scanLimit = scanLimit;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }

    public static class OrderTimeout {
        private boolean enabled = true;
        private Duration queuedTimeout = Duration.ofMinutes(10);
        private int batchSize = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getQueuedTimeout() {
            return queuedTimeout;
        }

        public void setQueuedTimeout(Duration queuedTimeout) {
            this.queuedTimeout = queuedTimeout;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class ConsumingRecovery {
        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(60);
        private Duration staleTimeout = Duration.ofMinutes(2);
        private int batchSize = 50;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public Duration getStaleTimeout() {
            return staleTimeout;
        }

        public void setStaleTimeout(Duration staleTimeout) {
            this.staleTimeout = staleTimeout;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }
    }

    public static class RedisRecovery {
        private boolean enabled = true;
        private Duration healthCheckDelay = Duration.ofSeconds(5);
        private int failureThreshold = 3;
        private int scanLimit = 100;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getHealthCheckDelay() {
            return healthCheckDelay;
        }

        public void setHealthCheckDelay(Duration healthCheckDelay) {
            this.healthCheckDelay = healthCheckDelay;
        }

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getScanLimit() {
            return scanLimit;
        }

        public void setScanLimit(int scanLimit) {
            this.scanLimit = scanLimit;
        }
    }

    public static class AdminAuth {
        private boolean enabled = false;
        private String token = "";
        private String headerName = "X-Admin-Token";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(String headerName) {
            this.headerName = headerName;
        }
    }

    public static class Snowflake {
        /** Distinct per application instance; inject via env MINI_SECKILL_WORKER_ID in multi-instance deployments. Range [0, 1023]. */
        private long workerId = 0L;
        /** Epoch start in epoch-millis. Default 2024-01-01T00:00:00Z. */
        private long epochMillis = 1704067200000L;

        public long getWorkerId() {
            return workerId;
        }

        public void setWorkerId(long workerId) {
            this.workerId = workerId;
        }

        public long getEpochMillis() {
            return epochMillis;
        }

        public void setEpochMillis(long epochMillis) {
            this.epochMillis = epochMillis;
        }
    }

    public static class AsyncLog {
        /** When false, seckill_log writes stay synchronous (useful for A/B benchmarking and debugging). */
        private boolean enabled = true;
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        /** Bounded queue; overflow is dropped and counted (seckill_log_async_total{result=dropped}), never blocks admission. */
        private int queueCapacity = 10000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
