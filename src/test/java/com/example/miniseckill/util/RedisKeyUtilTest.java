package com.example.miniseckill.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RedisKeyUtilTest {

    @Test
    void shouldBuildSeckillKeys() {
        assertEquals("seckill:stock:1001", RedisKeyUtil.stockKey(1001L));
        assertEquals("seckill:stock:1:1001", RedisKeyUtil.stockKey(1L, 1001L));
        assertEquals("seckill:stock:1:1001:bucket:3", RedisKeyUtil.stockBucketKey(1L, 1001L, 3));
        assertEquals("seckill:user:10001:sku:1001", RedisKeyUtil.userSkuKey(10001L, 1001L));
        assertEquals("seckill:user:1:10001:sku:1001", RedisKeyUtil.userSkuKey(1L, 10001L, 1001L));
        assertEquals("seckill:order:status:1:10001:1001", RedisKeyUtil.orderStatusKey(1L, 10001L, 1001L));
        assertEquals("seckill:token:1:10001:1001", RedisKeyUtil.tokenKey(1L, 10001L, 1001L));
        assertEquals("seckill:token:quota:1:1001", RedisKeyUtil.tokenQuotaKey(1L, 1001L));
        assertEquals("seckill:lock:stock:init:1001", RedisKeyUtil.stockInitLockKey(1001L));
        assertEquals("seckill:lock:stock:init:1:1001", RedisKeyUtil.stockInitLockKey(1L, 1001L));
        assertEquals("seckill:rate:sku:1:1001", RedisKeyUtil.rateSkuKey(1L, 1001L));
        assertEquals("seckill:rate:user:1:10001", RedisKeyUtil.rateUserKey(1L, 10001L));
        assertEquals("seckill:rate:ip:1:127.0.0.1", RedisKeyUtil.rateIpKey(1L, "127.0.0.1"));
        assertEquals("seckill:lock:redis:recovery:1:1001", RedisKeyUtil.redisRecoveryLockKey(1L, 1001L));
    }
}
