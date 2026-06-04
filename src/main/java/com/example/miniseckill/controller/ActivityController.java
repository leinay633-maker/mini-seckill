package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.ActivityResponse;
import com.example.miniseckill.service.ActivityService;
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
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping("/create")
    public Result<ActivityResponse> create(@RequestParam @Min(1) Long activityId,
                                           @RequestParam(required = false) String name,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return activityService.create(activityId, name, startTime, endTime);
    }

    @PostMapping("/start")
    public Result<ActivityResponse> start(@RequestParam @Min(1) Long activityId) {
        return activityService.start(activityId);
    }

    @PostMapping("/close")
    public Result<ActivityResponse> close(@RequestParam @Min(1) Long activityId) {
        return activityService.close(activityId);
    }

    @GetMapping("/query")
    public Result<ActivityResponse> query(@RequestParam @Min(1) Long activityId) {
        return Result.success(activityService.query(activityId));
    }
}
