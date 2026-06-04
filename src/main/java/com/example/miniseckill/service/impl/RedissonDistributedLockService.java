package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.service.DistributedLockService;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Redisson implementation of a Redis distributed lock with bounded wait and lease time.
 */
@Service
public class RedissonDistributedLockService implements DistributedLockService {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLockService.class);

    private final RedissonClient redissonClient;

    public RedissonDistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action) {
        RLock lock = redissonClient.getLock(lockKey);
        boolean locked = false;
        try {
            locked = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            if (!locked) {
                throw new BusinessException(429, "系统繁忙，请稍后重试");
            }
            return action.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(503, "获取分布式锁被中断，请稍后重试");
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("released distributed lock, key={}", lockKey);
            }
        }
    }
}
