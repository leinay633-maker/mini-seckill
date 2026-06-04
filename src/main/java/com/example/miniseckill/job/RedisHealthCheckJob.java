package com.example.miniseckill.job;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.service.RedisRecoveryStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Detects Redis outages and switches the seckill entrance into recovery mode.
 */
@Component
public class RedisHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(RedisHealthCheckJob.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final RedisRecoveryStateService redisRecoveryStateService;

    public RedisHealthCheckJob(StringRedisTemplate stringRedisTemplate,
                               SeckillProperties seckillProperties,
                               RedisRecoveryStateService redisRecoveryStateService) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
        this.redisRecoveryStateService = redisRecoveryStateService;
    }

    @Scheduled(fixedDelayString = "${seckill.redis-recovery.health-check-delay:5000}")
    public void checkRedisHealth() {
        SeckillProperties.RedisRecovery recovery = seckillProperties.getRedisRecovery();
        if (!recovery.isEnabled()) {
            return;
        }
        try {
            String pong = stringRedisTemplate.execute((RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                redisRecoveryStateService.recordHealthSuccess();
            }
        } catch (Exception ex) {
            int failures = redisRecoveryStateService.recordHealthFailure(shortError(ex));
            if (failures >= Math.max(1, recovery.getFailureThreshold())) {
                redisRecoveryStateService.markRecovering("Redis health check failed " + failures + " times: " + shortError(ex));
                log.warn("Redis entered recovery mode, consecutiveFailures={}, error={}", failures, ex.getMessage());
            }
        }
    }

    private String shortError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
