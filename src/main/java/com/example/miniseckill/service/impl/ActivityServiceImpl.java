package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.ActivityStatus;
import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.ActivityResponse;
import com.example.miniseckill.entity.SeckillActivity;
import com.example.miniseckill.mapper.SeckillActivityMapper;
import com.example.miniseckill.service.ActivityService;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Default activity service with simple lifecycle checks for interview demonstration.
 */
@Service
public class ActivityServiceImpl implements ActivityService {

    private final SeckillActivityMapper seckillActivityMapper;

    public ActivityServiceImpl(SeckillActivityMapper seckillActivityMapper) {
        this.seckillActivityMapper = seckillActivityMapper;
    }

    @Override
    public Result<ActivityResponse> create(Long activityId, String name, LocalDateTime startTime, LocalDateTime endTime) {
        validate(activityId, startTime, endTime);
        String resolvedName = StringUtils.hasText(name) ? name : "秒杀活动-" + activityId;
        seckillActivityMapper.upsert(
                activityId,
                resolvedName,
                ActivityStatus.NOT_STARTED.getCode(),
                startTime,
                endTime
        );
        return Result.success("活动已创建", query(activityId));
    }

    @Override
    public Result<ActivityResponse> start(Long activityId) {
        assertExists(activityId);
        seckillActivityMapper.updateStatus(activityId, ActivityStatus.RUNNING.getCode());
        return Result.success("活动已开始", query(activityId));
    }

    @Override
    public Result<ActivityResponse> close(Long activityId) {
        assertExists(activityId);
        seckillActivityMapper.updateStatus(activityId, ActivityStatus.CLOSED.getCode());
        return Result.success("活动已关闭", query(activityId));
    }

    @Override
    public ActivityResponse query(Long activityId) {
        SeckillActivity activity = seckillActivityMapper.selectByActivityId(activityId);
        if (activity == null) {
            throw new BusinessException(404, "活动不存在");
        }
        return toResponse(activity);
    }

    @Override
    public void assertRunning(Long activityId) {
        SeckillActivity activity = seckillActivityMapper.selectByActivityId(activityId);
        if (activity == null) {
            throw new BusinessException(404, "活动不存在");
        }
        ActivityStatus status = currentStatus(activity);
        if (status != ActivityStatus.RUNNING) {
            throw new BusinessException(403, "活动" + status.getText());
        }
    }

    @Override
    public void assertExists(Long activityId) {
        if (seckillActivityMapper.selectByActivityId(activityId) == null) {
            throw new BusinessException(404, "活动不存在");
        }
    }

    private void validate(Long activityId, LocalDateTime startTime, LocalDateTime endTime) {
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(400, "activityId 参数不合法");
        }
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BusinessException(400, "活动开始时间和结束时间不合法");
        }
    }

    private ActivityStatus currentStatus(SeckillActivity activity) {
        ActivityStatus stored = ActivityStatus.fromCode(activity.getStatus());
        if (stored == ActivityStatus.CLOSED) {
            return ActivityStatus.CLOSED;
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(activity.getStartTime())) {
            return ActivityStatus.NOT_STARTED;
        }
        if (now.isAfter(activity.getEndTime())) {
            return ActivityStatus.ENDED;
        }
        return stored == ActivityStatus.RUNNING ? ActivityStatus.RUNNING : ActivityStatus.NOT_STARTED;
    }

    private ActivityResponse toResponse(SeckillActivity activity) {
        ActivityStatus status = currentStatus(activity);
        ActivityResponse response = new ActivityResponse();
        response.setActivityId(activity.getActivityId());
        response.setName(activity.getName());
        response.setStatus(status.getCode());
        response.setStatusText(status.getText());
        response.setStartTime(activity.getStartTime());
        response.setEndTime(activity.getEndTime());
        return response;
    }
}
