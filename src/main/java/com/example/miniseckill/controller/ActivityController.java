package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.ActivityResponse;
import com.example.miniseckill.service.ActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for controlling seckill activity lifecycle.
 */
@Validated
@RestController
@RequestMapping("/api/activity")
@Tag(name = "Activity", description = "活动生命周期管理")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Operation(
            summary = "创建或更新秒杀活动",
            description = "创建指定 activityId 的活动窗口，用于本地演示活动状态校验。默认 SQL 已内置 activityId=1，可直接用于 smoke 流程。")
    @PostMapping("/create")
    public Result<ActivityResponse> create(@RequestParam @Min(1) Long activityId,
                                           @RequestParam(required = false) String name,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return activityService.create(activityId, name, startTime, endTime);
    }

    @Operation(
            summary = "启动秒杀活动",
            description = "将活动切换为进行中状态。token 获取和下单链路都会校验活动状态。")
    @PostMapping("/start")
    public Result<ActivityResponse> start(@RequestParam @Min(1) Long activityId) {
        return activityService.start(activityId);
    }

    @Operation(
            summary = "关闭秒杀活动",
            description = "将活动切换为关闭状态。关闭后入口不会继续发放 token 或接收下单。")
    @PostMapping("/close")
    public Result<ActivityResponse> close(@RequestParam @Min(1) Long activityId) {
        return activityService.close(activityId);
    }

    @Operation(
            summary = "查询秒杀活动",
            description = "查询活动状态和时间窗口，常用于 smoke-flow、压测前检查和面试演示。")
    @GetMapping("/query")
    public Result<ActivityResponse> query(@RequestParam @Min(1) Long activityId) {
        return Result.success(activityService.query(activityId));
    }
}
