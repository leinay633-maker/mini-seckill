package com.example.miniseckill.job;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes long-running queued orders so users do not stay in "queuing" forever.
 */
@Component
public class OrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);

    private final SeckillMessageMapper seckillMessageMapper;
    private final SeckillLogMapper seckillLogMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;

    public OrderTimeoutJob(SeckillMessageMapper seckillMessageMapper,
                           SeckillLogMapper seckillLogMapper,
                           CompensationRecordMapper compensationRecordMapper,
                           StringRedisTemplate stringRedisTemplate,
                           SeckillProperties seckillProperties) {
        this.seckillMessageMapper = seckillMessageMapper;
        this.seckillLogMapper = seckillLogMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
    }

    @Scheduled(fixedDelayString = "${seckill.order-timeout.fixed-delay:60000}")
    public void closeTimeoutOrders() {
        SeckillProperties.OrderTimeout timeout = seckillProperties.getOrderTimeout();
        if (!timeout.isEnabled()) {
            return;
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(timeout.getQueuedTimeout());
        List<SeckillMessageRecord> records = seckillMessageMapper.selectTimeoutCandidates(
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                cutoff,
                timeout.getBatchSize()
        );
        for (SeckillMessageRecord record : records) {
            markTimeout(record);
        }
    }

    private void markTimeout(SeckillMessageRecord record) {
        int updated = seckillMessageMapper.markTimeout(
                record.getRequestId(),
                MessageStatus.TIMEOUT.getCode(),
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                "queued order timeout"
        );
        if (updated != 1) {
            log.info("skip timeout side effects because message status changed, requestId={}", record.getRequestId());
            return;
        }
        stringRedisTemplate.opsForValue().set(
                RedisKeyUtil.orderStatusKey(record.getActivityId(), record.getUserId(), record.getSkuId()),
                String.valueOf(OrderStatus.TIMEOUT.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
        try {
            seckillLogMapper.insertLog(
                    record.getRequestId(),
                    record.getActivityId(),
                    record.getUserId(),
                    record.getSkuId(),
                    "ORDER_TIMEOUT"
            );
        } catch (Exception ex) {
            log.warn("insert timeout log failed, requestId={}", record.getRequestId(), ex);
        }
        try {
            CompensationRecord compensation = new CompensationRecord();
            compensation.setRequestId(record.getRequestId());
            compensation.setActivityId(record.getActivityId());
            compensation.setSkuId(record.getSkuId());
            compensation.setType("ORDER_TIMEOUT");
            compensation.setStatus("CLOSED");
            compensation.setDetail("queued order timeout");
            compensationRecordMapper.insert(compensation);
        } catch (Exception ex) {
            log.warn("insert timeout compensation failed, requestId={}", record.getRequestId(), ex);
        }
    }
}
