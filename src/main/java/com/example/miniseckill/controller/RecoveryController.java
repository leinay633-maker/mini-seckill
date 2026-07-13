package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.RedisRecoveryResponse;
import com.example.miniseckill.dto.RedisRecoveryStatusResponse;
import com.example.miniseckill.service.RedisRecoveryService;
import com.example.miniseckill.service.RedisRecoveryStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fault-drill endpoints for Redis outage status and stock recovery.
 */
@RestController
@RequestMapping("/api/recovery/redis")
@Tag(name = "Recovery", description = "Redis 故障恢复演练")
public class RecoveryController {

    private final RedisRecoveryStateService redisRecoveryStateService;
    private final RedisRecoveryService redisRecoveryService;

    public RecoveryController(RedisRecoveryStateService redisRecoveryStateService,
                              RedisRecoveryService redisRecoveryService) {
        this.redisRecoveryStateService = redisRecoveryStateService;
        this.redisRecoveryService = redisRecoveryService;
    }

    @Operation(
            summary = "查询 Redis 恢复状态",
            description = "查看当前应用是否处于 Redis 恢复态。恢复态下 token 和下单入口会 fail-closed，避免库存事实不清时继续放量。")
    @GetMapping("/status")
    public Result<RedisRecoveryStatusResponse> status() {
        return Result.success(redisRecoveryStateService.currentStatus());
    }

    @Operation(
            summary = "重建 Redis 秒杀库存",
            description = "按 MySQL 可用库存和未完成消息重建 Redis 总库存、分片库存和 token 资格池。用于故障演练，不代表 Redis 宕机无感恢复。")
    @PostMapping
    public Result<RedisRecoveryResponse> recover() {
        return Result.success(redisRecoveryService.recoverRedisStock());
    }
}
