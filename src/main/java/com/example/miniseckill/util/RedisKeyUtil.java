package com.example.miniseckill.util;

/**
 * Centralizes Redis key naming used by the seckill flow.
 */
public final class RedisKeyUtil {

    private RedisKeyUtil() {
    }

    public static String stockKey(Long skuId) {
        return "seckill:stock:" + skuId;
    }

    public static String stockKey(Long activityId, Long skuId) {
        return "seckill:stock:" + activityId + ":" + skuId;
    }

    public static String stockBucketKey(Long activityId, Long skuId, int bucket) {
        return "seckill:stock:" + activityId + ":" + skuId + ":bucket:" + bucket;
    }

    public static String userSkuKey(Long userId, Long skuId) {
        return "seckill:user:" + userId + ":sku:" + skuId;
    }

    public static String userSkuKey(Long activityId, Long userId, Long skuId) {
        return "seckill:user:" + activityId + ":" + userId + ":sku:" + skuId;
    }

    public static String orderStatusKey(Long activityId, Long userId, Long skuId) {
        return "seckill:order:status:" + activityId + ":" + userId + ":" + skuId;
    }

    public static String tokenKey(Long activityId, Long userId, Long skuId) {
        return "seckill:token:" + activityId + ":" + userId + ":" + skuId;
    }

    public static String tokenQuotaKey(Long activityId, Long skuId) {
        return "seckill:token:quota:" + activityId + ":" + skuId;
    }

    public static String stockInitLockKey(Long skuId) {
        return "seckill:lock:stock:init:" + skuId;
    }

    public static String stockInitLockKey(Long activityId, Long skuId) {
        return "seckill:lock:stock:init:" + activityId + ":" + skuId;
    }

    public static String rateSkuKey(Long activityId, Long skuId) {
        return "seckill:rate:sku:" + activityId + ":" + skuId;
    }

    public static String rateUserKey(Long activityId, Long userId) {
        return "seckill:rate:user:" + activityId + ":" + userId;
    }

    public static String rateIpKey(Long activityId, String clientIp) {
        return "seckill:rate:ip:" + activityId + ":" + clientIp;
    }

    public static String reconcileLockKey(Long activityId, Long skuId) {
        return "seckill:lock:reconcile:" + activityId + ":" + skuId;
    }

    public static String redisRecoveryLockKey(Long activityId, Long skuId) {
        return "seckill:lock:redis:recovery:" + activityId + ":" + skuId;
    }
}
