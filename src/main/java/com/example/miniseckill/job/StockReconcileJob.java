package com.example.miniseckill.job;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.DistributedLockService;
import com.example.miniseckill.service.SoldOutCacheService;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reconciles Redis entrance stock with MySQL facts while reserving unfinished MQ messages.
 */
@Component
public class StockReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(StockReconcileJob.class);

    private final SkuStockMapper skuStockMapper;
    private final SkuStockSegmentMapper skuStockSegmentMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final SeckillMessageMapper seckillMessageMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DistributedLockService distributedLockService;
    private final SeckillProperties seckillProperties;
    private final SoldOutCacheService soldOutCacheService;

    public StockReconcileJob(SkuStockMapper skuStockMapper,
                             SkuStockSegmentMapper skuStockSegmentMapper,
                             SeckillOrderMapper seckillOrderMapper,
                             SeckillMessageMapper seckillMessageMapper,
                             CompensationRecordMapper compensationRecordMapper,
                             StringRedisTemplate stringRedisTemplate,
                             DistributedLockService distributedLockService,
                             SeckillProperties seckillProperties,
                             SoldOutCacheService soldOutCacheService) {
        this.skuStockMapper = skuStockMapper;
        this.skuStockSegmentMapper = skuStockSegmentMapper;
        this.seckillOrderMapper = seckillOrderMapper;
        this.seckillMessageMapper = seckillMessageMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.distributedLockService = distributedLockService;
        this.seckillProperties = seckillProperties;
        this.soldOutCacheService = soldOutCacheService;
    }

    @Scheduled(fixedDelayString = "${seckill.reconcile.fixed-delay:60000}")
    public void reconcileStock() {
        SeckillProperties.Reconcile reconcile = seckillProperties.getReconcile();
        if (!reconcile.isEnabled()) {
            return;
        }

        int remaining = Math.max(1, reconcile.getScanLimit());
        int pageSize = Math.max(1, reconcile.getPageSize());
        long lastId = 0L;
        while (remaining > 0) {
            List<SkuStock> stocks = skuStockMapper.selectPageAfterId(lastId, Math.min(pageSize, remaining));
            if (stocks.isEmpty()) {
                return;
            }
            for (SkuStock stock : stocks) {
                lastId = stock.getId();
                try {
                    String lockKey = RedisKeyUtil.reconcileLockKey(stock.getActivityId(), stock.getSkuId());
                    distributedLockService.executeWithLock(lockKey, Duration.ZERO, Duration.ofSeconds(20), () -> {
                        reconcileOne(stock);
                        return null;
                    });
                } catch (BusinessException ex) {
                    log.debug("skip stock reconcile because lock is busy, activityId={}, skuId={}",
                            stock.getActivityId(), stock.getSkuId());
                } catch (Exception ex) {
                    insertCompensation(stock, "RECONCILE_FAILED", "FAILED", shortText(ex.getMessage(), 1024));
                    log.warn("stock reconcile failed, activityId={}, skuId={}, error={}",
                            stock.getActivityId(), stock.getSkuId(), ex.getMessage());
                }
            }
            remaining -= stocks.size();
            if (stocks.size() < pageSize) {
                return;
            }
        }
    }

    private void reconcileOne(SkuStock stock) {
        int mysqlAvailableStock = mysqlAvailableStock(stock);
        int mysqlSoldCount = mysqlSoldCount(stock);
        if (seckillProperties.getMysqlStockSegment().isEnabled()
                && skuStockSegmentMapper.countSegments(stock.getActivityId(), stock.getSkuId()) > 0) {
            skuStockMapper.syncFromSegments(stock.getActivityId(), stock.getSkuId());
        }

        long successOrderCount = seckillOrderMapper.countByActivitySkuStatus(
                stock.getActivityId(),
                stock.getSkuId(),
                OrderStatus.SUCCESS.getCode()
        );
        if (successOrderCount != mysqlSoldCount) {
            insertCompensation(stock, "MYSQL_ORDER_STOCK_MISMATCH", "WAIT_MANUAL_CHECK",
                    "soldCount=" + mysqlSoldCount + ", successOrderCount=" + successOrderCount);
            log.warn("mysql stock/order mismatch, activityId={}, skuId={}, soldCount={}, successOrderCount={}",
                    stock.getActivityId(), stock.getSkuId(), mysqlSoldCount, successOrderCount);
        }

        long unfinished = seckillMessageMapper.countRecoveringUnfinishedByActivitySku(
                stock.getActivityId(),
                stock.getSkuId(),
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
        int expectedRedisStock = expectedRedisStock(mysqlAvailableStock, unfinished);

        String redisKey = RedisKeyUtil.stockKey(stock.getActivityId(), stock.getSkuId());
        Integer redisStock = seckillProperties.getStockShard().isEnabled()
                ? sumBucketStock(stock.getActivityId(), stock.getSkuId())
                : parseStock(stringRedisTemplate.opsForValue().get(redisKey));
        if (redisStock == null || !redisStock.equals(expectedRedisStock)) {
            writeRedisStock(stock.getActivityId(), stock.getSkuId(), expectedRedisStock);
            insertCompensation(stock, "REDIS_STOCK_REPAIR", "DONE",
                    "redisStock=" + redisStock + ", mysqlAvailableStock=" + mysqlAvailableStock + ", unfinishedMessages=" + unfinished + ", expectedRedisStock=" + expectedRedisStock);
            log.warn("repair redis stock from mysql, activityId={}, skuId={}, redisStock={}, mysqlAvailableStock={}, unfinishedMessages={}, expectedRedisStock={}",
                    stock.getActivityId(), stock.getSkuId(), redisStock, mysqlAvailableStock, unfinished, expectedRedisStock);
        }
    }

    private int expectedRedisStock(int mysqlAvailableStock, long unfinished) {
        long expected = Math.max(0L, (long) mysqlAvailableStock - unfinished);
        return expected > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) expected;
    }

    private void writeRedisStock(Long activityId, Long skuId, int stock) {
        stringRedisTemplate.opsForValue().set(RedisKeyUtil.stockKey(activityId, skuId), String.valueOf(stock));
        if (stock > 0) {
            soldOutCacheService.clear(activityId, skuId);
        } else {
            soldOutCacheService.markSoldOut(activityId, skuId);
        }
        if (!seckillProperties.getStockShard().isEnabled()) {
            return;
        }
        List<String> bucketKeys = stockBucketKeys(activityId, skuId);
        deleteKeysOneByOne(bucketKeys);
        int count = bucketKeys.size();
        int base = stock / count;
        int remainder = stock % count;
        for (int i = 0; i < count; i++) {
            int bucketStock = base + (i < remainder ? 1 : 0);
            stringRedisTemplate.opsForValue().set(bucketKeys.get(i), String.valueOf(bucketStock));
        }
    }

    private Integer sumBucketStock(Long activityId, Long skuId) {
        int sum = 0;
        boolean found = false;
        for (String bucketKey : stockBucketKeys(activityId, skuId)) {
            Integer bucketStock = parseStock(stringRedisTemplate.opsForValue().get(bucketKey));
            if (bucketStock != null) {
                found = true;
                sum += bucketStock;
            }
        }
        return found ? sum : null;
    }

    private List<String> stockBucketKeys(Long activityId, Long skuId) {
        int count = Math.max(1, seckillProperties.getStockShard().getBucketCount());
        List<String> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            keys.add(RedisKeyUtil.stockBucketKey(activityId, skuId, i));
        }
        return keys;
    }

    private void deleteKeysOneByOne(List<String> keys) {
        for (String key : keys) {
            stringRedisTemplate.delete(key);
        }
    }

    private Integer parseStock(String redisValue) {
        if (redisValue == null || redisValue.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(redisValue);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int mysqlAvailableStock(SkuStock stock) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()
                || skuStockSegmentMapper.countSegments(stock.getActivityId(), stock.getSkuId()) == 0) {
            return stock.getAvailableStock();
        }
        return skuStockSegmentMapper.sumAvailableStock(stock.getActivityId(), stock.getSkuId());
    }

    private int mysqlSoldCount(SkuStock stock) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()
                || skuStockSegmentMapper.countSegments(stock.getActivityId(), stock.getSkuId()) == 0) {
            return stock.getSoldCount();
        }
        return skuStockSegmentMapper.sumSoldCount(stock.getActivityId(), stock.getSkuId());
    }

    private void insertCompensation(SkuStock stock, String type, String status, String detail) {
        try {
            CompensationRecord record = new CompensationRecord();
            record.setActivityId(stock.getActivityId());
            record.setSkuId(stock.getSkuId());
            record.setType(type);
            record.setStatus(status);
            record.setDetail(detail == null ? "" : detail);
            compensationRecordMapper.insert(record);
        } catch (Exception ex) {
            log.warn("insert reconcile compensation failed, activityId={}, skuId={}",
                    stock.getActivityId(), stock.getSkuId(), ex);
        }
    }

    private String shortText(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
