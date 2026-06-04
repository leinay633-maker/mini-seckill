package com.example.miniseckill.dto;

/**
 * RabbitMQ message for async order creation.
 */
public class SeckillMessage {

    private String requestId;
    private Long activityId;
    private Long userId;
    private Long skuId;
    private Long timestamp;

    public SeckillMessage() {
    }

    public SeckillMessage(String requestId, Long activityId, Long userId, Long skuId, Long timestamp) {
        this.requestId = requestId;
        this.activityId = activityId;
        this.userId = userId;
        this.skuId = skuId;
        this.timestamp = timestamp;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
