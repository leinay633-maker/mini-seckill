package com.example.miniseckill.service;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.ActivityResponse;
import java.time.LocalDateTime;

/**
 * Manages the lifecycle of a seckill activity.
 */
public interface ActivityService {

    Result<ActivityResponse> create(Long activityId, String name, LocalDateTime startTime, LocalDateTime endTime);

    Result<ActivityResponse> start(Long activityId);

    Result<ActivityResponse> close(Long activityId);

    ActivityResponse query(Long activityId);

    void assertRunning(Long activityId);

    void assertExists(Long activityId);
}
