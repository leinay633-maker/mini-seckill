package com.example.miniseckill.service;

import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.RateLimitRuleRequest;
import com.example.miniseckill.entity.RateLimitRule;

/**
 * Provides runtime SKU-level rate limit overrides.
 */
public interface DynamicRateLimitService {

    RateLimitPlan effectivePlan(Long activityId, Long skuId);

    RateLimitRule upsert(RateLimitRuleRequest request);

    RateLimitRule query(Long activityId, Long skuId);
}
