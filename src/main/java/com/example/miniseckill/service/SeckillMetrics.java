package com.example.miniseckill.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Centralizes counters and bounded-cardinality admission-stage timings. */
@Component
public class SeckillMetrics {

    private static final Logger log = LoggerFactory.getLogger(SeckillMetrics.class);
    private final MeterRegistry meterRegistry;

    public SeckillMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void admission(String result) {
        meterRegistry.counter("seckill_admission_total", "result", result).increment();
    }

    public void redisStock(String result) {
        meterRegistry.counter("seckill_redis_stock_total", "result", result).increment();
    }

    public void mq(String result) {
        meterRegistry.counter("seckill_mq_total", "result", result).increment();
    }

    public void order(String result) {
        meterRegistry.counter("seckill_order_total", "result", result).increment();
    }

    public void replay(String result) {
        meterRegistry.counter("seckill_replay_total", "result", result).increment();
    }

    public void orderId(String result) {
        meterRegistry.counter("seckill_order_id_total", "result", result).increment();
    }

    public void asyncLog(String result) {
        meterRegistry.counter("seckill_log_async_total", "result", result).increment();
    }

    public enum CapacityStage {
        ACTIVITY_LOOKUP("activity_lookup"), TOKEN_ORDER_LOOKUP("token_order_lookup"),
        MESSAGE_INSERT("message_insert"), INITIAL_PUBLISH("initial_publish");

        private final String tag;

        CapacityStage(String tag) {
            this.tag = tag;
        }
    }

    /** Includes Hikari waiting for SQL stages; not a server-side SQL execution timer. */
    public void capacityStage(CapacityStage stage, long elapsedNanos) {
        try {
            meterRegistry.timer("seckill_capacity_stage", "stage", stage.tag)
                    .record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ex) {
            // In particular, a metrics failure after INSERT must not enter Redis compensation.
            log.warn("capacity timing failed, stage={}", stage, ex);
        }
    }

    public void activityCache(String result) {
        try {
            meterRegistry.counter("seckill_activity_cache_total", "result", result).increment();
        } catch (RuntimeException ex) {
            log.warn("activity cache metric failed, result={}", result, ex);
        }
    }
    /** Bounded outcome labels only; never request IDs or SKU IDs. */
    public void coordination(String result) {
        meterRegistry.counter("seckill_coordination", "result", result).increment();
    }
}
