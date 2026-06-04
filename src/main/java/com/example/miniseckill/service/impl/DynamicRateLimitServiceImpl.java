package com.example.miniseckill.service.impl;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.RateLimitRuleRequest;
import com.example.miniseckill.entity.RateLimitRule;
import com.example.miniseckill.mapper.RateLimitRuleMapper;
import com.example.miniseckill.service.DynamicRateLimitService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * Resolves rate limit rules from DB with a short Caffeine cache.
 */
@Service
public class DynamicRateLimitServiceImpl implements DynamicRateLimitService {

    private final RateLimitRuleMapper rateLimitRuleMapper;
    private final SeckillProperties seckillProperties;
    private final Cache<String, RateLimitRule> ruleCache;

    public DynamicRateLimitServiceImpl(RateLimitRuleMapper rateLimitRuleMapper,
                                       SeckillProperties seckillProperties) {
        this.rateLimitRuleMapper = rateLimitRuleMapper;
        this.seckillProperties = seckillProperties;
        Duration ttl = seckillProperties.getDynamicRateLimit().getCacheTtl();
        this.ruleCache = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterWrite(ttl == null ? Duration.ofSeconds(5) : ttl)
                .build();
    }

    @Override
    public RateLimitPlan effectivePlan(Long activityId, Long skuId) {
        SeckillProperties.RateLimit fallback = seckillProperties.getRateLimit();
        if (!fallback.isEnabled()) {
            return new RateLimitPlan(false, fallback.getWindow(), fallback.getSkuLimit(), fallback.getUserLimit(), fallback.getIpLimit(), "application.yml");
        }
        if (!seckillProperties.getDynamicRateLimit().isEnabled()) {
            return fallbackPlan(fallback);
        }
        String cacheKey = cacheKey(activityId, skuId);
        RateLimitRule rule = ruleCache.getIfPresent(cacheKey);
        if (rule == null) {
            rule = rateLimitRuleMapper.selectByActivitySku(activityId, skuId);
            if (rule != null) {
                ruleCache.put(cacheKey, rule);
            }
        }
        if (rule == null) {
            return fallbackPlan(fallback);
        }
        if (rule.getEnabled() != null && rule.getEnabled() == 0) {
            return new RateLimitPlan(false, Duration.ofSeconds(Math.max(1, rule.getWindowSeconds())), 0, 0, 0, "db-rule");
        }
        return new RateLimitPlan(
                true,
                Duration.ofSeconds(Math.max(1, valueOr(rule.getWindowSeconds(), (int) Math.max(1, fallback.getWindow().toSeconds())))),
                valueOr(rule.getSkuLimit(), fallback.getSkuLimit()),
                valueOr(rule.getUserLimit(), fallback.getUserLimit()),
                valueOr(rule.getIpLimit(), fallback.getIpLimit()),
                "db-rule"
        );
    }

    @Override
    public RateLimitRule upsert(RateLimitRuleRequest request) {
        RateLimitRule rule = new RateLimitRule();
        rule.setActivityId(request.getActivityId());
        rule.setSkuId(request.getSkuId());
        rule.setEnabled(Boolean.FALSE.equals(request.getEnabled()) ? 0 : 1);
        rule.setWindowSeconds(request.getWindowSeconds());
        rule.setSkuLimit(request.getSkuLimit());
        rule.setUserLimit(request.getUserLimit());
        rule.setIpLimit(request.getIpLimit());
        rateLimitRuleMapper.upsert(rule);
        ruleCache.invalidate(cacheKey(request.getActivityId(), request.getSkuId()));
        return rateLimitRuleMapper.selectByActivitySku(request.getActivityId(), request.getSkuId());
    }

    @Override
    public RateLimitRule query(Long activityId, Long skuId) {
        return rateLimitRuleMapper.selectByActivitySku(activityId, skuId);
    }

    private RateLimitPlan fallbackPlan(SeckillProperties.RateLimit fallback) {
        return new RateLimitPlan(
                true,
                fallback.getWindow(),
                fallback.getSkuLimit(),
                fallback.getUserLimit(),
                fallback.getIpLimit(),
                "application.yml"
        );
    }

    private int valueOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String cacheKey(Long activityId, Long skuId) {
        return activityId + ":" + skuId;
    }
}
