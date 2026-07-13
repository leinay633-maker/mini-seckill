package com.example.miniseckill.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Centralizes Micrometer counters for admission, Redis stock, MQ, and order outcomes.
 */
@Component
public class SeckillMetrics {

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
}
