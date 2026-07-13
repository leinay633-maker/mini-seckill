package com.example.miniseckill.dto;

/**
 * Short-lived token required before entering the seckill order endpoint.
 */
public class TokenResponse {

    private Long activityId;
    private Long userId;
    private Long skuId;
    private String token;
    private String orderPath;
    private Long expiresInSeconds;

    public TokenResponse() {
    }

    public TokenResponse(Long activityId, Long userId, Long skuId, String token, Long expiresInSeconds) {
        this(activityId, userId, skuId, token, null, expiresInSeconds);
    }

    public TokenResponse(Long activityId, Long userId, Long skuId, String token, String orderPath, Long expiresInSeconds) {
        this.activityId = activityId;
        this.userId = userId;
        this.skuId = skuId;
        this.token = token;
        this.orderPath = orderPath;
        this.expiresInSeconds = expiresInSeconds;
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

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getOrderPath() {
        return orderPath;
    }

    public void setOrderPath(String orderPath) {
        this.orderPath = orderPath;
    }

    public Long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(Long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }
}
