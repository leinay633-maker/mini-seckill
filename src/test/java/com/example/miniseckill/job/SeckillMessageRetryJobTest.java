package com.example.miniseckill.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.SeckillMetrics;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeckillMessageRetryJobTest {

    private static final String REQUEST_ID = "retry-001";
    private SeckillProperties properties;
    private SeckillMessageRetryJob job;

    @Mock private SeckillMessageMapper messageMapper;
    @Mock private CompensationRecordMapper compensationRecordMapper;
    @Mock private SeckillProducer producer;
    @Mock private SeckillMetrics metrics;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        job = new SeckillMessageRetryJob(messageMapper, compensationRecordMapper, producer, properties, metrics);
    }

    @Test
    void publishesRetryableMessageAndUsesConfiguredStates() {
        when(messageMapper.selectRetryable(eq(MessageStatus.PENDING.getCode()), eq(MessageStatus.SENDING.getCode()),
                eq(MessageStatus.FAILED.getCode()), eq(MessageStatus.CONFIRM_FAILED.getCode()), eq(MessageStatus.RETURNED.getCode()),
                eq(properties.getMessageRetry().getMaxRetry()), eq(properties.getMessageRetry().getBatchSize())))
                .thenReturn(List.of(record(1)));
        job.retrySendMessage();
        verify(producer).send(any());
        verify(metrics).mq("retry_published");
    }

    @Test
    void marksExhaustedMessageDeadAndCreatesReplayCompensationOnlyAfterWinningCas() {
        exhausted();
        when(messageMapper.markDead(REQUEST_ID, MessageStatus.DEAD.getCode(), MessageStatus.CONSUMED.getCode(),
                "message retry exhausted")).thenReturn(1);
        job.retrySendMessage();
        verify(messageMapper).markDead(REQUEST_ID, MessageStatus.DEAD.getCode(), MessageStatus.CONSUMED.getCode(),
                "message retry exhausted");
        verify(compensationRecordMapper).insert(any());
        verify(metrics).mq("retry_exhausted_dead");
    }

    @Test
    void staleExhaustionScanDoesNotEmitCompensationWhenAnotherPathWon() {
        exhausted();
        job.retrySendMessage();
        verify(compensationRecordMapper, never()).insert(any());
        verify(metrics, never()).mq("retry_exhausted_dead");
    }

    @Test
    void stalePublishFailureDoesNotReportAStateChangeThatDidNotHappen() {
        retryFailure();
        job.retrySendMessage();
        verify(messageMapper).markFailedForRetry(eq(REQUEST_ID), eq(MessageStatus.FAILED.getCode()), eq("send failed"), any());
        verify(metrics, never()).mq("retry_failed");
    }

    @Test
    void eligiblePublishFailureIsRecordedAndCounted() {
        retryFailure();
        when(messageMapper.markFailedForRetry(eq(REQUEST_ID), eq(MessageStatus.FAILED.getCode()), eq("send failed"), any()))
                .thenReturn(1);
        job.retrySendMessage();
        verify(metrics).mq("retry_failed");
    }

    private void retryFailure() {
        when(messageMapper.selectRetryable(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(record(1)));
        doThrow(new IllegalStateException("send failed")).when(producer).send(any());
    }

    private void exhausted() {
        when(messageMapper.selectRetryExhausted(eq(MessageStatus.PENDING.getCode()), eq(MessageStatus.SENDING.getCode()),
                eq(MessageStatus.FAILED.getCode()), eq(MessageStatus.CONFIRM_FAILED.getCode()), eq(MessageStatus.RETURNED.getCode()),
                eq(properties.getMessageRetry().getMaxRetry()), eq(properties.getMessageRetry().getBatchSize())))
                .thenReturn(List.of(record(properties.getMessageRetry().getMaxRetry())));
    }

    private SeckillMessageRecord record(int retryCount) {
        SeckillMessageRecord record = new SeckillMessageRecord();
        record.setRequestId(REQUEST_ID);
        record.setActivityId(1L);
        record.setUserId(100L);
        record.setSkuId(1001L);
        record.setStatus(MessageStatus.FAILED.getCode());
        record.setRetryCount(retryCount);
        record.setNextRetryAt(LocalDateTime.now().minusSeconds(1));
        return record;
    }
}
