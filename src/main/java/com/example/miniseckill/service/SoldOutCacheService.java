package com.example.miniseckill.service;

/**
 * Local short-lived sold-out marker used to avoid repeatedly hitting Redis after stock reaches zero.
 */
public interface SoldOutCacheService {

    boolean isSoldOut(Long activityId, Long skuId);

    void markSoldOut(Long activityId, Long skuId);

    void clear(Long activityId, Long skuId);
}
