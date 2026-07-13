package com.example.miniseckill.service;

/**
 * Generates globally unique, roughly time-ordered order ids.
 *
 * <p>Implementations must stay unique across multiple application instances,
 * which is why the previous {@code currentTimeMillis() * 1000 + sequence} helper
 * was replaced: two instances could mint the same id within one millisecond.
 */
public interface OrderIdGenerator {

    long nextId();
}
