package com.example.miniseckill.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small local order id generator for the personal demo project.
 */
public final class OrderIdGenerator {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);

    private OrderIdGenerator() {
    }

    public static long nextId() {
        int sequence = SEQUENCE.updateAndGet(value -> value >= 999 ? 0 : value + 1);
        return System.currentTimeMillis() * 1000 + sequence;
    }
}
