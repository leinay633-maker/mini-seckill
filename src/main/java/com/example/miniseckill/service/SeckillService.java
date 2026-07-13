package com.example.miniseckill.service;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.dto.StockViewResponse;
import com.example.miniseckill.dto.TokenResponse;

/**
 * Service facade for stock initialization and seckill order admission.
 */
public interface SeckillService {

    Result<Void> initStock(Long activityId, Long skuId, Integer stock);

    Result<Void> warmupStock(Long activityId, Long skuId);

    Result<TokenResponse> createOrderToken(Long activityId, Long userId, Long skuId, String clientIp);

    Result<Void> placeOrder(SeckillOrderRequest request, String clientIp);

    Result<Void> placeOrderWithPath(String orderPath, SeckillOrderRequest request, String clientIp);

    StockViewResponse queryStock(Long activityId, Long skuId);
}
