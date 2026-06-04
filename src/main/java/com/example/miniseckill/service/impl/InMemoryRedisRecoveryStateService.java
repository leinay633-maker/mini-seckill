package com.example.miniseckill.service.impl;

import com.example.miniseckill.dto.RedisRecoveryStatusResponse;
import com.example.miniseckill.service.RedisRecoveryStateService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * Keeps Redis recovery state in JVM memory so outages can be detected while Redis is unavailable.
 */
@Service
public class InMemoryRedisRecoveryStateService implements RedisRecoveryStateService {

    private final AtomicBoolean recovering = new AtomicBoolean(false);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile String lastReason = "Redis health has not reported failures";
    private volatile LocalDateTime lastFailureTime;
    private volatile LocalDateTime lastStateChangeTime = LocalDateTime.now();

    @Override
    public boolean isRecovering() {
        return recovering.get();
    }

    @Override
    public void markRecovering(String reason) {
        if (recovering.compareAndSet(false, true)) {
            lastStateChangeTime = LocalDateTime.now();
        }
        lastReason = reason;
    }

    @Override
    public void markRecovered() {
        recovering.set(false);
        consecutiveFailures.set(0);
        lastReason = "Redis stock has been rebuilt from MySQL";
        lastStateChangeTime = LocalDateTime.now();
    }

    @Override
    public void recordHealthSuccess() {
        consecutiveFailures.set(0);
    }

    @Override
    public int recordHealthFailure(String reason) {
        lastReason = reason;
        lastFailureTime = LocalDateTime.now();
        return consecutiveFailures.incrementAndGet();
    }

    @Override
    public RedisRecoveryStatusResponse currentStatus() {
        return new RedisRecoveryStatusResponse(
                recovering.get(),
                consecutiveFailures.get(),
                lastReason,
                lastFailureTime,
                lastStateChangeTime
        );
    }
}
