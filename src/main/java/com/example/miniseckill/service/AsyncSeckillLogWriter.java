package com.example.miniseckill.service;

import com.example.miniseckill.config.SeckillProperties;
import org.springframework.stereotype.Component;

/**
 * Entry point for off-path seckill_log writes.
 *
 * <p>The hot order/token endpoints emit several audit rows per request; doing those inserts
 * synchronously made MySQL part of every request's latency. This writer routes them to a bounded
 * async executor instead. The log is a side channel — losing a row on overflow is acceptable,
 * blocking an order is not. Set {@code seckill.async-log.enabled=false} to force synchronous writes
 * for A/B benchmarking or debugging.
 *
 * <p>The actual {@code @Async} boundary lives in {@link SeckillLogWriteExecutor} (a separate bean),
 * because {@code @Async} is a no-op when a bean calls its own annotated method.
 */
@Component
public class AsyncSeckillLogWriter {

    private final SeckillLogWriteExecutor executor;
    private final boolean asyncEnabled;

    public AsyncSeckillLogWriter(SeckillLogWriteExecutor executor, SeckillProperties seckillProperties) {
        this.executor = executor;
        this.asyncEnabled = seckillProperties.getAsyncLog().isEnabled();
    }

    /** Writes an audit row; async when enabled, synchronous fallback otherwise. Never throws to the caller. */
    public void write(String requestId, Long activityId, Long userId, Long skuId, String result) {
        if (asyncEnabled) {
            executor.writeAsync(requestId, activityId, userId, skuId, result);
        } else {
            executor.write(requestId, activityId, userId, skuId, result);
        }
    }
}
