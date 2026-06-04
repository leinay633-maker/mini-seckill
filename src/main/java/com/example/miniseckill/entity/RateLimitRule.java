package com.example.miniseckill.entity;

import java.time.LocalDateTime;

/**
 * Dynamic rate limit override for one activity and SKU.
 */
public class RateLimitRule {

    private Long id;
    private Long activityId;
    private Long skuId;
    private Integer enabled;
    private Integer windowSeconds;
    private Integer skuLimit;
    private Integer userLimit;
    private Integer ipLimit;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getActivityId() {
        return activityId;
    }

    public void setActivityId(Long activityId) {
        this.activityId = activityId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public Integer getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(Integer windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public Integer getSkuLimit() {
        return skuLimit;
    }

    public void setSkuLimit(Integer skuLimit) {
        this.skuLimit = skuLimit;
    }

    public Integer getUserLimit() {
        return userLimit;
    }

    public void setUserLimit(Integer userLimit) {
        this.userLimit = userLimit;
    }

    public Integer getIpLimit() {
        return ipLimit;
    }

    public void setIpLimit(Integer ipLimit) {
        this.ipLimit = ipLimit;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
