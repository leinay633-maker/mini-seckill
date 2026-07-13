package com.example.miniseckill.job;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.entity.SeckillOrder;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers stale CONSUMING messages after a consumer crash or process restart.
 */
@Component
public class ConsumingMessageRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(ConsumingMessageRecoveryJob.class);
    private static final String STALE_CONSUMING_ERROR = "stale consuming recovered for retry";

    private final SeckillMessageMapper seckillMessageMapper;
    private final SeckillOrderMapper seckillOrderMapper;
    private final SeckillLogMapper seckillLogMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final SeckillMetrics seckillMetrics;

    public ConsumingMessageRecoveryJob(SeckillMessageMapper seckillMessageMapper,
                                       SeckillOrderMapper seckillOrderMapper,
                                       SeckillLogMapper seckillLogMapper,
                                       StringRedisTemplate stringRedisTemplate,
                                       SeckillProperties seckillProperties,
                                       SeckillMetrics seckillMetrics) {
        this.seckillMessageMapper = seckillMessageMapper;
        this.seckillOrderMapper = seckillOrderMapper;
        this.seckillLogMapper = seckillLogMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
        this.seckillMetrics = seckillMetrics;
    }

    @Scheduled(fixedDelayString = "${seckill.consuming-recovery.fixed-delay:60000}")
    public void recoverStaleConsumingMessages() {
        SeckillProperties.ConsumingRecovery recovery = seckillProperties.getConsumingRecovery();
        if (!recovery.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(recovery.getStaleTimeout());
        List<SeckillMessageRecord> records = seckillMessageMapper.selectStaleConsuming(
                MessageStatus.CONSUMING.getCode(),
                cutoff,
                recovery.getBatchSize()
        );
        for (SeckillMessageRecord record : records) {
            recover(record);
        }
    }

    private void recover(SeckillMessageRecord record) {
        SeckillOrder order = seckillOrderMapper.selectByUserSku(
                record.getActivityId(),
                record.getUserId(),
                record.getSkuId()
        );
        if (order != null) {
            markConsumed(record);
            return;
        }
        markRetryable(record);
    }

    private void markConsumed(SeckillMessageRecord record) {
        int updated = seckillMessageMapper.markConsumedFromConsuming(
                record.getRequestId(),
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
        if (updated != 1) {
            log.info("skip stale CONSUMING consume recovery because status changed, requestId={}", record.getRequestId());
            return;
        }
        stringRedisTemplate.opsForValue().set(
                RedisKeyUtil.orderStatusKey(record.getActivityId(), record.getUserId(), record.getSkuId()),
                String.valueOf(OrderStatus.SUCCESS.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
        safeLog(record, "CONSUMING_RECOVERED_CONSUMED");
        seckillMetrics.mq("consuming_recovered_consumed");
    }

    private void markRetryable(SeckillMessageRecord record) {
        int updated = seckillMessageMapper.markFailedFromConsuming(
                record.getRequestId(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONSUMING.getCode(),
                STALE_CONSUMING_ERROR,
                LocalDateTime.now()
        );
        if (updated != 1) {
            log.info("skip stale CONSUMING retry recovery because status changed, requestId={}", record.getRequestId());
            return;
        }
        safeLog(record, "CONSUMING_RECOVERED_RETRY");
        seckillMetrics.mq("consuming_recovered_retry");
    }

    private void safeLog(SeckillMessageRecord record, String result) {
        try {
            seckillLogMapper.insertLog(
                    record.getRequestId(),
                    record.getActivityId(),
                    record.getUserId(),
                    record.getSkuId(),
                    result
            );
        } catch (Exception ex) {
            log.warn("insert consuming recovery log failed, requestId={}, result={}", record.getRequestId(), result, ex);
        }
    }
}
