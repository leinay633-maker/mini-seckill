package com.example.miniseckill.service;

import com.example.miniseckill.dto.OrderQueryResponse;
import com.example.miniseckill.dto.SeckillMessage;

/**
 * Service facade for async order persistence and order query.
 */
public interface OrderService {

    void createOrderFromMessage(SeckillMessage message);

    void createOrderFromConsumingMessage(SeckillMessage message);

    /**
     * Resolves a duplicate against the durable business key, not the exception type.
     * A missing or non-terminal order is an error, never evidence of success.
     */
    void reconcileExistingOrderFromConsumingMessage(SeckillMessage message);

    /**
     * Atomically persists a terminal FAILED order and closes the CONSUMING message.
     * A duplicate is resolved against the existing order's actual status. Persistence
     * errors propagate: the consumer must not ACK a failure that was not recorded.
     */
    void recordFailedOrder(SeckillMessage message);

    OrderQueryResponse queryOrder(Long activityId, Long userId, Long skuId);
}
