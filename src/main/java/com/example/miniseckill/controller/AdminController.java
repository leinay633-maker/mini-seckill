package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.MessageReplayResponse;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.RateLimitRuleRequest;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.RateLimitRule;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.service.DynamicRateLimitService;
import com.example.miniseckill.service.MessageAdminService;
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

    @PostMapping("/rate-limit")
    public Result<RateLimitRule> upsertRateLimit(@Valid @RequestBody RateLimitRuleRequest request) {
        return Result.success(dynamicRateLimitService.upsert(request));
    }

    @GetMapping("/rate-limit")
    public Result<RateLimitPlan> queryEffectiveRateLimit(@RequestParam @Min(1) Long activityId,
                                                         @RequestParam @Min(1) Long skuId) {
        return Result.success(dynamicRateLimitService.effectivePlan(activityId, skuId));
    }

    @GetMapping("/rate-limit/raw")
    public Result<RateLimitRule> queryRawRateLimit(@RequestParam @Min(1) Long activityId,
                                                   @RequestParam @Min(1) Long skuId) {
        return Result.success(dynamicRateLimitService.query(activityId, skuId));
    }

    @PostMapping("/messages/replay")
    public Result<MessageReplayResponse> replayOne(@RequestParam String requestId) {
        return Result.success(messageAdminService.replayOne(requestId));
    }

    @PostMapping("/messages/replay-dead")
    public Result<MessageReplayResponse> replayDead(@RequestParam(defaultValue = "20") @Min(1) Integer limit) {
        return Result.success(messageAdminService.replayDead(limit));
    }

    @GetMapping("/compensations")
    public Result<List<CompensationRecord>> compensations(@RequestParam(defaultValue = "20") @Min(1) Integer limit) {
        return Result.success(compensationRecordMapper.selectRecent(Math.min(limit, 200)));
    }
}
