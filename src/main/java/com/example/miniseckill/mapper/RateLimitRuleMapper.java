package com.example.miniseckill.mapper;

import com.example.miniseckill.entity.RateLimitRule;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for runtime rate limit overrides.
 */
@Mapper
public interface RateLimitRuleMapper {

    @Insert("""
            INSERT INTO seckill_rate_limit_rule (activity_id, sku_id, enabled, window_seconds, sku_limit, user_limit, ip_limit, created_at, updated_at)
            VALUES (#{activityId}, #{skuId}, #{enabled}, #{windowSeconds}, #{skuLimit}, #{userLimit}, #{ipLimit}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                enabled = VALUES(enabled),
                window_seconds = VALUES(window_seconds),
                sku_limit = VALUES(sku_limit),
                user_limit = VALUES(user_limit),
                ip_limit = VALUES(ip_limit),
                updated_at = NOW()
            """)
    int upsert(RateLimitRule rule);

    @Select("""
            SELECT id, activity_id, sku_id, enabled, window_seconds, sku_limit, user_limit, ip_limit, created_at, updated_at
            FROM seckill_rate_limit_rule
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            LIMIT 1
            """)
    RateLimitRule selectByActivitySku(@Param("activityId") Long activityId, @Param("skuId") Long skuId);
}
