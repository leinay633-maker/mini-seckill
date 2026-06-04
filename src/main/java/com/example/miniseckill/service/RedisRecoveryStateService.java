package com.example.miniseckill.service;

import com.example.miniseckill.dto.RedisRecoveryStatusResponse;

/**
 * Tracks Redis recovery state without depending on Redis itself.
 */
public interface RedisRecoveryStateService {

    boolean isRecovering();

    void markRecovering(String reason);

    void markRecovered();

    void recordHealthSuccess();

    int recordHealthFailure(String reason);

    RedisRecoveryStatusResponse currentStatus();
}
