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
    private RedisRecovery redisRecovery = new RedisRecovery();

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

    public RedisRecovery getRedisRecovery() {
        return redisRecovery;
    }

    public void setRedisRecovery(RedisRecovery redisRecovery) {
        this.redisRecovery = redisRecovery;
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
}
