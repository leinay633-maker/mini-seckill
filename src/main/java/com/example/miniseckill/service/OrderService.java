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
     * Persists a terminal FAILED order row so a failed order stays queryable after the Redis status
     * key's TTL expires. Idempotent: a duplicate (activity,user,sku) is ignored.
     */
    void recordFailedOrder(SeckillMessage message);

    OrderQueryResponse queryOrder(Long activityId, Long userId, Long skuId);
}
