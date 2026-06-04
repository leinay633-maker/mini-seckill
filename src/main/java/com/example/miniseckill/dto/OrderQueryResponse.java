package com.example.miniseckill.dto;

/**
 * Order status returned by the query endpoint.
 */
public class OrderQueryResponse {

    private Long userId;
    private Long activityId;
    private Long skuId;
    private Long orderId;
    private Integer status;
    private String statusText;

    public OrderQueryResponse() {
    }

    public OrderQueryResponse(Long activityId, Long userId, Long skuId, Long orderId, Integer status, String statusText) {
        this.activityId = activityId;
        this.userId = userId;
        this.skuId = skuId;
        this.orderId = orderId;
        this.status = status;
        this.statusText = statusText;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
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

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getStatusText() {
        return statusText;
    }

    public void setStatusText(String statusText) {
        this.statusText = statusText;
    }
}
