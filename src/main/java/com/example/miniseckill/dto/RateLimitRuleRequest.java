package com.example.miniseckill.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for updating one SKU's runtime rate limit rule.
 */
public class RateLimitRuleRequest {

    @NotNull
    @Min(1)
    private Long activityId;

    @NotNull
    @Min(1)
    private Long skuId;

    private Boolean enabled = true;

    @NotNull
    @Min(1)
    private Integer windowSeconds;

    @NotNull
    @Min(0)
    private Integer skuLimit;

    @NotNull
    @Min(0)
    private Integer userLimit;

    @NotNull
    @Min(0)
    private Integer ipLimit;

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

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
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
}
