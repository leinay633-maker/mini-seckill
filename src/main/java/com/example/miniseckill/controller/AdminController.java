package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.OpenApiConfig;
import com.example.miniseckill.dto.MessageReplayResponse;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.RateLimitRuleRequest;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.RateLimitRule;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.service.DynamicRateLimitService;
import com.example.miniseckill.service.MessageAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal admin endpoints for interview demos: dynamic rate limits, message replay, and compensation records.
 */
@Validated
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "动态限流、消息回放和补偿记录")
@SecurityRequirement(name = OpenApiConfig.ADMIN_TOKEN_AUTH)
public class AdminController {

    private final DynamicRateLimitService dynamicRateLimitService;
    private final MessageAdminService messageAdminService;
    private final CompensationRecordMapper compensationRecordMapper;

    public AdminController(DynamicRateLimitService dynamicRateLimitService,
                           MessageAdminService messageAdminService,
                           CompensationRecordMapper compensationRecordMapper) {
        this.dynamicRateLimitService = dynamicRateLimitService;
        this.messageAdminService = messageAdminService;
        this.compensationRecordMapper = compensationRecordMapper;
    }

    @Operation(
            summary = "新增或更新动态限流规则",
            description = "按活动和 SKU 写入动态限流规则，用于演示活动 SKU、用户和 IP 维度限流的在线调整。需要 X-Admin-Token 或 Authorization: Bearer <admin token>。")
    @PostMapping("/rate-limit")
    public Result<RateLimitRule> upsertRateLimit(@Valid @RequestBody RateLimitRuleRequest request) {
        return Result.success(dynamicRateLimitService.upsert(request));
    }

    @Operation(
            summary = "查询生效限流方案",
            description = "查询当前活动 SKU 最终生效的限流配置，包含数据库动态规则和默认配置的合并结果。")
    @GetMapping("/rate-limit")
    public Result<RateLimitPlan> queryEffectiveRateLimit(@RequestParam @Min(1) Long activityId,
                                                         @RequestParam @Min(1) Long skuId) {
        return Result.success(dynamicRateLimitService.effectivePlan(activityId, skuId));
    }

    @Operation(
            summary = "查询原始动态限流规则",
            description = "只查询数据库中保存的动态限流规则，不做默认配置兜底，方便排查规则是否已经写入。")
    @GetMapping("/rate-limit/raw")
    public Result<RateLimitRule> queryRawRateLimit(@RequestParam @Min(1) Long activityId,
                                                   @RequestParam @Min(1) Long skuId) {
        return Result.success(dynamicRateLimitService.query(activityId, skuId));
    }

    @Operation(
            summary = "按 requestId 回放单条本地消息",
            description = "对指定 requestId 的本地消息执行一次回放，用于 MQ confirm 失败、return、dead 状态后的人工恢复演示。")
    @PostMapping("/messages/replay")
    public Result<MessageReplayResponse> replayOne(@RequestParam String requestId) {
        return Result.success(messageAdminService.replayOne(requestId));
    }

    @Operation(
            summary = "批量回放 DEAD 消息",
            description = "按 limit 扫描 DEAD 消息并尝试回放，适合故障演练后的批量收敛，不等同于生产级人工审核后台。")
    @PostMapping("/messages/replay-dead")
    public Result<MessageReplayResponse> replayDead(@RequestParam(defaultValue = "20") @Min(1) Integer limit) {
        return Result.success(messageAdminService.replayDead(limit));
    }

    @Operation(
            summary = "查询最近补偿记录",
            description = "查询最近的补偿记录，用于观察消费失败、库存异常、对账修复等异常链路的收敛情况。")
    @GetMapping("/compensations")
    public Result<List<CompensationRecord>> compensations(@RequestParam(defaultValue = "20") @Min(1) Integer limit) {
        return Result.success(compensationRecordMapper.selectRecent(Math.min(limit, 200)));
    }
}
