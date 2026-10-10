package com.example.miniseckill.job;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.SeckillMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resends local message records that were accepted but not sent successfully.
 */
@Component
public class SeckillMessageRetryJob {

    private static final Logger log = LoggerFactory.getLogger(SeckillMessageRetryJob.class);

    private final SeckillMessageMapper seckillMessageMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final SeckillProducer seckillProducer;
    private final SeckillProperties seckillProperties;
    private final SeckillMetrics seckillMetrics;

    public SeckillMessageRetryJob(SeckillMessageMapper seckillMessageMapper,
                                  CompensationRecordMapper compensationRecordMapper,
                                  SeckillProducer seckillProducer,
                                  SeckillProperties seckillProperties,
                                  SeckillMetrics seckillMetrics) {
        this.seckillMessageMapper = seckillMessageMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.seckillProducer = seckillProducer;
        this.seckillProperties = seckillProperties;
        this.seckillMetrics = seckillMetrics;
    }

    @Scheduled(fixedDelayString = "${seckill.message-retry.fixed-delay:15000}")
    public void retrySendMessage() {
        SeckillProperties.MessageRetry retry = seckillProperties.getMessageRetry();
        if (!retry.isEnabled()) {
            return;
        }

        List<SeckillMessageRecord> records = seckillMessageMapper.selectRetryable(
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                retry.getMaxRetry(),
                retry.getBatchSize()
        );
        for (SeckillMessageRecord record : records) {
            resend(record);
        }
        markRetryExhausted(retry);
    }

    private void resend(SeckillMessageRecord record) {
        SeckillMessage message = new SeckillMessage(
                record.getRequestId(),
                record.getActivityId(),
                record.getUserId(),
                record.getSkuId(),
                System.currentTimeMillis()
        );
        try {
            if (seckillProducer.send(message)) {
                seckillMetrics.mq("retry_published");
                log.info("COORD_RETRY_PUBLISHED requestId={}", record.getRequestId());
            }
        } catch (RuntimeException ex) {
            // The token-qualified producer path either persisted failure or left a leased attempt.
            // A second status-only update here would let an old worker corrupt a new attempt.
            log.warn("COORD_RETRY_DEFERRED requestId={}", record.getRequestId(), ex);
        }
    }

    private void markRetryExhausted(SeckillProperties.MessageRetry retry) {
        List<SeckillMessageRecord> exhausted = seckillMessageMapper.selectRetryExhausted(
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                retry.getMaxRetry(),
                retry.getBatchSize()
        );
        for (SeckillMessageRecord record : exhausted) {
            int updated;
            try { updated=seckillMessageMapper.exhaustSend(record.getRequestId(), retry.getMaxRetry()); }
            catch (RuntimeException ex) { log.warn("COORD_EXHAUST_DEFERRED requestId={}",record.getRequestId(),ex); continue; }
            if (updated != 1) {
                // The scan is a snapshot, not authority to close a now-consuming or terminal row.
                continue;
            }
            insertCompensation(record, "MQ_RETRY_EXHAUSTED", "WAIT_REVIEW", "retryCount=" + record.getRetryCount());
            seckillMetrics.mq("retry_exhausted_dead");
        }
    }

    private void insertCompensation(SeckillMessageRecord record, String type, String status, String detail) {
        try {
            CompensationRecord compensation = new CompensationRecord();
            compensation.setRequestId(record.getRequestId());
            compensation.setActivityId(record.getActivityId());
            compensation.setSkuId(record.getSkuId());
            compensation.setType(type);
            compensation.setStatus(status);
            compensation.setDetail(detail);
            compensationRecordMapper.insert(compensation);
        } catch (Exception ex) {
            log.warn("insert retry compensation failed, requestId={}", record.getRequestId(), ex);
        }
    }

    private String shortError(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 512 ? message.substring(0, 512) : message;
    }
}
