package com.example.miniseckill.service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Executes low-frequency management operations under a Redis distributed lock.
 */
public interface DistributedLockService {

    <T> T executeWithLock(String lockKey, Duration waitTime, Duration leaseTime, Supplier<T> action);
}
