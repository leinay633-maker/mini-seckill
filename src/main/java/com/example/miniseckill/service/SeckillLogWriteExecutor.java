package com.example.miniseckill.service;

import com.example.miniseckill.config.AsyncConfig;
import com.example.miniseckill.mapper.SeckillLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Carries the actual {@code @Async} boundary for seckill_log writes.
 *
 * <p>Deliberately a separate bean: {@code @Async} only takes effect when the method is invoked
 * through the Spring proxy, i.e. from a <em>different</em> bean. If AsyncSeckillLogWriter called its
 * own {@code @Async} method it would be a plain {@code this} call and run synchronously on the
 * request thread — the exact trap this split avoids.
 */
@Component
public class SeckillLogWriteExecutor {

    private static final Logger log = LoggerFactory.getLogger(SeckillLogWriteExecutor.class);

    private final SeckillLogMapper seckillLogMapper;
    private final SeckillMetrics seckillMetrics;

    public SeckillLogWriteExecutor(SeckillLogMapper seckillLogMapper, SeckillMetrics seckillMetrics) {
        this.seckillLogMapper = seckillLogMapper;
        this.seckillMetrics = seckillMetrics;
    }

    @Async(AsyncConfig.SECKILL_LOG_EXECUTOR)
    public void writeAsync(String requestId, Long activityId, Long userId, Long skuId, String result) {
        write(requestId, activityId, userId, skuId, result);
    }

    /** Synchronous write shared by the async path and the disabled-fallback path. Never throws. */
    public void write(String requestId, Long activityId, Long userId, Long skuId, String result) {
        try {
            seckillLogMapper.insertLog(requestId, activityId, userId, skuId, result);
            seckillMetrics.asyncLog("written");
        } catch (Exception ex) {
            seckillMetrics.asyncLog("failed");
            log.warn("seckill log write failed, requestId={}, result={}", requestId, result, ex);
        }
    }
}
