package com.example.miniseckill.dto;

import java.time.LocalDateTime;

/**
 * Result of rebuilding Redis stock from MySQL facts and unfinished MQ messages.
 */
public class RedisRecoveryResponse {

    private int scannedSkuCount;
    private int recoveredSkuCount;
    private int skippedSkuCount;
    private long totalExpectedRedisStock;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String message;

    public int getScannedSkuCount() {
        return scannedSkuCount;
    }

    public void setScannedSkuCount(int scannedSkuCount) {
        this.scannedSkuCount = scannedSkuCount;
    }

    public int getRecoveredSkuCount() {
        return recoveredSkuCount;
    }

    public void setRecoveredSkuCount(int recoveredSkuCount) {
        this.recoveredSkuCount = recoveredSkuCount;
    }

    public int getSkippedSkuCount() {
        return skippedSkuCount;
    }

    public void setSkippedSkuCount(int skippedSkuCount) {
        this.skippedSkuCount = skippedSkuCount;
    }

    public long getTotalExpectedRedisStock() {
        return totalExpectedRedisStock;
    }

    public void setTotalExpectedRedisStock(long totalExpectedRedisStock) {
        this.totalExpectedRedisStock = totalExpectedRedisStock;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
