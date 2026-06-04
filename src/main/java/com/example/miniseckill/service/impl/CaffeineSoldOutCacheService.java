package com.example.miniseckill.service.impl;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.service.SoldOutCacheService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * Caffeine-backed local sold-out marker with a short TTL.
 */
@Service
public class CaffeineSoldOutCacheService implements SoldOutCacheService {

    private final SeckillProperties seckillProperties;
    private final Cache<String, Boolean> soldOutCache;

    public CaffeineSoldOutCacheService(SeckillProperties seckillProperties) {
        this.seckillProperties = seckillProperties;
        Duration ttl = seckillProperties.getSoldOutLocalCache().getTtl();
        this.soldOutCache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(ttl == null ? Duration.ofSeconds(5) : ttl)
                .build();
    }

    @Override
    public boolean isSoldOut(Long activityId, Long skuId) {
        if (!seckillProperties.getSoldOutLocalCache().isEnabled()) {
            return false;
        }
        return Boolean.TRUE.equals(soldOutCache.getIfPresent(cacheKey(activityId, skuId)));
    }

    @Override
    public void markSoldOut(Long activityId, Long skuId) {
        if (seckillProperties.getSoldOutLocalCache().isEnabled()) {
            soldOutCache.put(cacheKey(activityId, skuId), Boolean.TRUE);
        }
    }

    @Override
    public void clear(Long activityId, Long skuId) {
        soldOutCache.invalidate(cacheKey(activityId, skuId));
    }

    private String cacheKey(Long activityId, Long skuId) {
        return activityId + ":" + skuId;
    }
}
