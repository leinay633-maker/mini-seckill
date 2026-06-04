package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.RedisRecoveryResponse;
import com.example.miniseckill.dto.RedisRecoveryStatusResponse;
import com.example.miniseckill.service.RedisRecoveryService;
import com.example.miniseckill.service.RedisRecoveryStateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fault-drill endpoints for Redis outage status and stock recovery.
 */
@RestController
@RequestMapping("/api/recovery/redis")
public class RecoveryController {

    private final RedisRecoveryStateService redisRecoveryStateService;
    private final RedisRecoveryService redisRecoveryService;

    public RecoveryController(RedisRecoveryStateService redisRecoveryStateService,
                              RedisRecoveryService redisRecoveryService) {
        this.redisRecoveryStateService = redisRecoveryStateService;
        this.redisRecoveryService = redisRecoveryService;
    }

    @GetMapping("/status")
    public Result<RedisRecoveryStatusResponse> status() {
        return Result.success(redisRecoveryStateService.currentStatus());
    }

    @PostMapping
    public Result<RedisRecoveryResponse> recover() {
        return Result.success(redisRecoveryService.recoverRedisStock());
    }
}
