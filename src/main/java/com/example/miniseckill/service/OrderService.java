package com.example.miniseckill.service;

import com.example.miniseckill.dto.OrderQueryResponse;
import com.example.miniseckill.dto.SeckillMessage;

/**
 * Service facade for async order persistence and order query.
 */
public interface OrderService {

    void createOrderFromMessage(SeckillMessage message);

    void createOrderFromConsumingMessage(SeckillMessage message);

    OrderQueryResponse queryOrder(Long activityId, Long userId, Long skuId);
}
