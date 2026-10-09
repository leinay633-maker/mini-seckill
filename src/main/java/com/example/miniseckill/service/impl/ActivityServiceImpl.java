package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.ActivityStatus;
import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.dto.ActivityResponse;
import com.example.miniseckill.entity.SeckillActivity;
import com.example.miniseckill.mapper.SeckillActivityMapper;
import com.example.miniseckill.service.ActivityService;
import com.example.miniseckill.service.SeckillMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/** Activity commands remain authoritative; only admission reads use a bounded snapshot cache. */
@Service
public class ActivityServiceImpl implements ActivityService {

    private final SeckillActivityMapper seckillActivityMapper;
    private final SeckillMetrics metrics;
    private final Cache<Long, ActivitySnapshot> admissionCache;
    private final long ttlNanos;
    private final Ticker ticker;
    private final Clock clock;
    // Fixed-size lock striping avoids an unbounded per-activity lock registry.
    // Hits do not lock; cold loads and lifecycle mutations share a stripe.
    private final Object[] stripes = new Object[256];

    @Autowired
    public ActivityServiceImpl(SeckillActivityMapper seckillActivityMapper,
                               AdmissionCapacityProperties properties,
                               SeckillMetrics metrics) {
        this(seckillActivityMapper, properties, metrics, Ticker.systemTicker(), Clock.systemDefaultZone());
    }

    ActivityServiceImpl(SeckillActivityMapper mapper, AdmissionCapacityProperties properties,
                        SeckillMetrics metrics, Ticker ticker, Clock clock) {
        this.seckillActivityMapper = mapper;
        this.metrics = metrics;
        this.ticker = ticker;
        this.clock = clock;
        this.ttlNanos = properties.getActivityCacheTtl().toNanos();
        this.admissionCache = Caffeine.newBuilder()
                .maximumSize(properties.getActivityCacheMaxEntries())
                .expireAfterWrite(properties.getActivityCacheTtl())
                .ticker(ticker)
                .build();
        Arrays.setAll(stripes, ignored -> new Object());
    }

    @Override
    public Result<ActivityResponse> create(Long activityId, String name, LocalDateTime startTime, LocalDateTime endTime) {
        validate(activityId, startTime, endTime);
        String resolvedName = StringUtils.hasText(name) ? name : "秒杀活动-" + activityId;
        mutate(activityId, () -> seckillActivityMapper.upsert(
                activityId, resolvedName, ActivityStatus.NOT_STARTED.getCode(), startTime, endTime));
        return Result.success("活动已创建", query(activityId));
    }

    @Override
    public Result<ActivityResponse> start(Long activityId) {
        mutate(activityId, () -> {
            assertExists(activityId);
            seckillActivityMapper.updateStatus(activityId, ActivityStatus.RUNNING.getCode());
        });
        return Result.success("活动已开始", query(activityId));
    }

    @Override
    public Result<ActivityResponse> close(Long activityId) {
        mutate(activityId, () -> {
            assertExists(activityId);
            seckillActivityMapper.updateStatus(activityId, ActivityStatus.CLOSED.getCode());
        });
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
        ActivitySnapshot activity = admissionSnapshot(activityId);
        if (!activity.exists()) {
            throw new BusinessException(404, "活动不存在");
        }
        // Cache metadata, never a derived RUNNING boolean: time boundaries are checked on every call.
        ActivityStatus status = currentStatus(activity.status(), activity.startTime(), activity.endTime());
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

    private ActivitySnapshot admissionSnapshot(Long activityId) {
        // Never publish an enclosing transaction's uncommitted activity snapshot to other threads.
        if (ttlNanos == 0 || TransactionSynchronizationManager.isActualTransactionActive()) {
            metrics.activityCache("disabled");
            return loadSnapshot(activityId);
        }
        ActivitySnapshot cached = freshSnapshot(activityId);
        if (cached != null) {
            metrics.activityCache("hit");
            return cached;
        }
        metrics.activityCache("miss");
        synchronized (stripe(activityId)) {
            cached = freshSnapshot(activityId);
            if (cached != null) {
                return cached;
            }
            ActivitySnapshot loaded = loadSnapshot(activityId);
            // Age is measured from BEFORE SQL, not from cache insertion. A slow read must not
            // republish an old RUNNING snapshot for another full TTL after it finally completes.
            if (ticker.read() - loaded.readStartedNanos() < ttlNanos) {
                admissionCache.put(activityId, loaded);
            }
            return loaded;
        }
    }

    private ActivitySnapshot freshSnapshot(Long activityId) {
        ActivitySnapshot cached = admissionCache.getIfPresent(activityId);
        return cached != null && ticker.read() - cached.readStartedNanos() < ttlNanos ? cached : null;
    }

    private ActivitySnapshot loadSnapshot(Long activityId) {
        long started = ticker.read();
        try {
            SeckillActivity activity = seckillActivityMapper.selectByActivityId(activityId);
            return activity == null
                    ? new ActivitySnapshot(false, null, null, null, started)
                    : new ActivitySnapshot(true, activity.getStatus(), activity.getStartTime(), activity.getEndTime(), started);
        } finally {
            metrics.capacityStage(SeckillMetrics.CapacityStage.ACTIVITY_LOOKUP, ticker.read() - started);
        }
    }

    private void mutate(Long activityId, Runnable command) {
        synchronized (stripe(activityId)) {
            try {
                command.run();
            } finally {
                // Also invalidate on an ambiguous/failed write. A pre-close loader cannot finish
                // after this invalidation because it holds the same stripe during its SQL read.
                admissionCache.invalidate(activityId);
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            synchronized (stripe(activityId)) {
                                admissionCache.invalidate(activityId);
                            }
                        }
                    });
                }
            }
        }
    }

    private Object stripe(Long activityId) {
        return stripes[Math.floorMod(activityId.hashCode(), stripes.length)];
    }

    private void validate(Long activityId, LocalDateTime startTime, LocalDateTime endTime) {
        if (activityId == null || activityId <= 0) {
            throw new BusinessException(400, "activityId 参数不合法");
        }
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            throw new BusinessException(400, "活动开始时间和结束时间不合法");
        }
    }

    private ActivityStatus currentStatus(Integer status, LocalDateTime startTime, LocalDateTime endTime) {
        ActivityStatus stored = ActivityStatus.fromCode(status);
        if (stored == ActivityStatus.CLOSED) {
            return ActivityStatus.CLOSED;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.isBefore(startTime)) {
            return ActivityStatus.NOT_STARTED;
        }
        if (now.isAfter(endTime)) {
            return ActivityStatus.ENDED;
        }
        return stored == ActivityStatus.RUNNING ? ActivityStatus.RUNNING : ActivityStatus.NOT_STARTED;
    }

    private ActivityResponse toResponse(SeckillActivity activity) {
        ActivityStatus status = currentStatus(activity.getStatus(), activity.getStartTime(), activity.getEndTime());
        ActivityResponse response = new ActivityResponse();
        response.setActivityId(activity.getActivityId());
        response.setName(activity.getName());
        response.setStatus(status.getCode());
        response.setStatusText(status.getText());
        response.setStartTime(activity.getStartTime());
        response.setEndTime(activity.getEndTime());
        return response;
    }

    private record ActivitySnapshot(boolean exists, Integer status, LocalDateTime startTime,
                                    LocalDateTime endTime, long readStartedNanos) {
    }
}
