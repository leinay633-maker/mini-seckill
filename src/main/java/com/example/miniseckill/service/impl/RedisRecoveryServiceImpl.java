package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RedisRecoveryResponse;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.DistributedLockService;
import com.example.miniseckill.service.RedisRecoveryService;
import com.example.miniseckill.service.RedisRecoveryStateService;
import com.example.miniseckill.service.SoldOutCacheService;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Rebuilds Redis total and bucket stock from MySQL available stock minus unfinished messages.
 */
@Service
public class RedisRecoveryServiceImpl implements RedisRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(RedisRecoveryServiceImpl.class);

    private final SkuStockMapper skuStockMapper;
    private final SkuStockSegmentMapper skuStockSegmentMapper;
    private final SeckillMessageMapper seckillMessageMapper;
    private final SeckillLogMapper seckillLogMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final DistributedLockService distributedLockService;
    private final SeckillProperties seckillProperties;
    private final RedisRecoveryStateService redisRecoveryStateService;
    private final SoldOutCacheService soldOutCacheService;

    public RedisRecoveryServiceImpl(SkuStockMapper skuStockMapper,
                                    SkuStockSegmentMapper skuStockSegmentMapper,
                                    SeckillMessageMapper seckillMessageMapper,
                                    SeckillLogMapper seckillLogMapper,
                                    CompensationRecordMapper compensationRecordMapper,
                                    StringRedisTemplate stringRedisTemplate,
                                    DistributedLockService distributedLockService,
                                    SeckillProperties seckillProperties,
                                    RedisRecoveryStateService redisRecoveryStateService,
                                    SoldOutCacheService soldOutCacheService) {
        this.skuStockMapper = skuStockMapper;
        this.skuStockSegmentMapper = skuStockSegmentMapper;
        this.seckillMessageMapper = seckillMessageMapper;
        this.seckillLogMapper = seckillLogMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.distributedLockService = distributedLockService;
        this.seckillProperties = seckillProperties;
        this.redisRecoveryStateService = redisRecoveryStateService;
        this.soldOutCacheService = soldOutCacheService;
    }

    @Override
    public RedisRecoveryResponse recoverRedisStock() {
        redisRecoveryStateService.markRecovering("manual Redis stock recovery is running");
        RedisRecoveryResponse response = new RedisRecoveryResponse();
        response.setStartedAt(LocalDateTime.now());

        List<SkuStock> stocks = skuStockMapper.selectRecentStocks(Math.max(1, seckillProperties.getRedisRecovery().getScanLimit()));
        response.setScannedSkuCount(stocks.size());

        int failedCount = 0;
        for (SkuStock stock : stocks) {
            try {
                String lockKey = RedisKeyUtil.redisRecoveryLockKey(stock.getActivityId(), stock.getSkuId());
                Integer expectedRedisStock = distributedLockService.executeWithLock(
                        lockKey,
                        Duration.ZERO,
                        Duration.ofSeconds(30),
                        () -> rebuildOne(stock)
                );
                response.setRecoveredSkuCount(response.getRecoveredSkuCount() + 1);
                response.setTotalExpectedRedisStock(response.getTotalExpectedRedisStock() + expectedRedisStock);
            } catch (BusinessException ex) {
                failedCount++;
                response.setSkippedSkuCount(response.getSkippedSkuCount() + 1);
                log.warn("skip Redis stock recovery, activityId={}, skuId={}, error={}",
                        stock.getActivityId(), stock.getSkuId(), ex.getMessage());
            } catch (Exception ex) {
                failedCount++;
                response.setSkippedSkuCount(response.getSkippedSkuCount() + 1);
                log.warn("Redis stock recovery failed, activityId={}, skuId={}, error={}",
                        stock.getActivityId(), stock.getSkuId(), ex.getMessage());
            }
        }

        response.setFinishedAt(LocalDateTime.now());
        if (failedCount > 0) {
            response.setMessage("Redis 恢复未完成，仍有 SKU 重建失败");
            redisRecoveryStateService.markRecovering(response.getMessage());
            throw new BusinessException(503, response.getMessage());
        }

        redisRecoveryStateService.markRecovered();
        response.setMessage("Redis 库存恢复完成，入口可以继续放量");
        return response;
    }

    private int rebuildOne(SkuStock stock) {
        long unfinished = countRecoveringUnfinished(stock.getActivityId(), stock.getSkuId());
        int mysqlAvailableStock = mysqlAvailableStock(stock);
        int expectedRedisStock = expectedRedisStock(mysqlAvailableStock, unfinished);
        writeRedisStock(stock.getActivityId(), stock.getSkuId(), expectedRedisStock);
        writeTokenQuota(stock.getActivityId(), stock.getSkuId(), expectedRedisStock);
        safeLog(stock.getActivityId(), stock.getSkuId(), "REDIS_RECOVERY_REBUILD");
        insertCompensation(stock, "REDIS_RECOVERY_REBUILD", "DONE",
                "mysqlAvailableStock=" + mysqlAvailableStock + ", unfinishedMessages=" + unfinished + ", expectedRedisStock=" + expectedRedisStock);
        log.warn("rebuild Redis stock, activityId={}, skuId={}, mysqlAvailableStock={}, unfinishedMessages={}, expectedRedisStock={}",
                stock.getActivityId(), stock.getSkuId(), mysqlAvailableStock, unfinished, expectedRedisStock);
        return expectedRedisStock;
    }

    private long countRecoveringUnfinished(Long activityId, Long skuId) {
        return seckillMessageMapper.countRecoveringUnfinishedByActivitySku(
                activityId,
                skuId,
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
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

    private void writeTokenQuota(Long activityId, Long skuId, int stock) {
        SeckillProperties.AntiBrush antiBrush = seckillProperties.getAntiBrush();
        if (!antiBrush.isEnabled() || !antiBrush.isTokenQuotaEnabled()) {
            return;
        }
        long multiplier = Math.max(1, antiBrush.getTokenQuotaMultiplier());
        long quota = Math.max(0L, (long) stock * multiplier);
        stringRedisTemplate.opsForValue().set(RedisKeyUtil.tokenQuotaKey(activityId, skuId), String.valueOf(quota));
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

    private void safeLog(Long activityId, Long skuId, String result) {
        try {
            seckillLogMapper.insertLog(UUID.randomUUID().toString(), activityId, 0L, skuId, result);
        } catch (Exception ex) {
            log.warn("insert Redis recovery log failed, activityId={}, skuId={}", activityId, skuId, ex);
        }
    }

    private int mysqlAvailableStock(SkuStock stock) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()
                || skuStockSegmentMapper.countSegments(stock.getActivityId(), stock.getSkuId()) == 0) {
            return stock.getAvailableStock();
        }
        return skuStockSegmentMapper.sumAvailableStock(stock.getActivityId(), stock.getSkuId());
    }

    private void insertCompensation(SkuStock stock, String type, String status, String detail) {
        try {
            CompensationRecord record = new CompensationRecord();
            record.setActivityId(stock.getActivityId());
            record.setSkuId(stock.getSkuId());
            record.setType(type);
            record.setStatus(status);
            record.setDetail(detail);
            compensationRecordMapper.insert(record);
        } catch (Exception ex) {
            log.warn("insert Redis recovery compensation failed, activityId={}, skuId={}",
                    stock.getActivityId(), stock.getSkuId(), ex);
        }
    }
}
