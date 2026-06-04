package com.example.miniseckill.dto;

import java.time.LocalDateTime;

/**
 * Current Redis health and recovery state exposed for fault drills.
 */
public class RedisRecoveryStatusResponse {

    private boolean recovering;
    private int consecutiveFailures;
    private String lastReason;
    private LocalDateTime lastFailureTime;
    private LocalDateTime lastStateChangeTime;

    public RedisRecoveryStatusResponse() {
    }

    public RedisRecoveryStatusResponse(boolean recovering,
                                       int consecutiveFailures,
                                       String lastReason,
                                       LocalDateTime lastFailureTime,
                                       LocalDateTime lastStateChangeTime) {
        this.recovering = recovering;
        this.consecutiveFailures = consecutiveFailures;
        this.lastReason = lastReason;
        this.lastFailureTime = lastFailureTime;
        this.lastStateChangeTime = lastStateChangeTime;
    }

    public boolean isRecovering() {
        return recovering;
    }

    public void setRecovering(boolean recovering) {
        this.recovering = recovering;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getLastReason() {
        return lastReason;
    }

    public void setLastReason(String lastReason) {
        this.lastReason = lastReason;
    }

    public LocalDateTime getLastFailureTime() {
        return lastFailureTime;
    }

    public void setLastFailureTime(LocalDateTime lastFailureTime) {
        this.lastFailureTime = lastFailureTime;
    }

    public LocalDateTime getLastStateChangeTime() {
        return lastStateChangeTime;
    }

    public void setLastStateChangeTime(LocalDateTime lastStateChangeTime) {
        this.lastStateChangeTime = lastStateChangeTime;
    }
}
