package com.example.miniseckill.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class ConsumingMessageRecoveryJobTest {

    private static final String REQUEST_ID = "req-001";
    private static final long ACTIVITY_ID = 1L;
    private static final long USER_ID = 10001L;
    private static final long SKU_ID = 1001L;

    @Mock private SeckillMessageMapper messages;
    @Mock private SeckillOrderMapper orders;
    @Mock private SeckillLogMapper logs;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private SeckillMetrics metrics;
    private SeckillProperties properties;
    private ConsumingMessageRecoveryJob job;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        job = new ConsumingMessageRecoveryJob(messages, orders, logs, redis, properties, metrics);
    }

    @Test
    void recoverStaleConsumingMarksConsumedOnlyWhenSuccessfulOrderExists() {
        batch(record());
        when(orders.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(order(OrderStatus.SUCCESS));
        when(messages.markConsumedFromConsuming(REQUEST_ID, MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode())).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        job.recoverStaleConsumingMessages();
        verify(values).set(RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID),
                String.valueOf(OrderStatus.SUCCESS.getCode()), properties.getOrderStatusTtl());
        verify(logs).insertLog(REQUEST_ID, ACTIVITY_ID, USER_ID, SKU_ID, "CONSUMING_RECOVERED_CONSUMED");
        verify(metrics).mq("consuming_recovered_consumed");
        verify(messages, never()).markFailedFromConsuming(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void recoverStaleConsumingMarksFailedForRetryWhenOrderDoesNotExist() {
        batch(record());
        when(messages.markFailedFromConsuming(eq(REQUEST_ID), eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONSUMING.getCode()), eq("stale consuming recovered for retry"), any())).thenReturn(1);
        job.recoverStaleConsumingMessages();
        verify(logs).insertLog(REQUEST_ID, ACTIVITY_ID, USER_ID, SKU_ID, "CONSUMING_RECOVERED_RETRY");
        verify(metrics).mq("consuming_recovered_retry");
        verify(redis, never()).opsForValue();
        verify(messages, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
    }

    @Test
    void recoverStaleConsumingSkipsSideEffectsWhenConsumedUpdateMisses() {
        batch(record());
        when(orders.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(order(OrderStatus.SUCCESS));
        job.recoverStaleConsumingMessages();
        verify(redis, never()).opsForValue();
        verify(logs, never()).insertLog(any(), any(), any(), any(), any());
        verify(metrics, never()).mq(any());
    }

    @Test
    void recoverStaleConsumingDoesNothingWhenDisabled() {
        properties.getConsumingRecovery().setEnabled(false);
        job.recoverStaleConsumingMessages();
        verify(messages, never()).selectStaleConsuming(anyInt(), any(), anyInt());
    }

    @Test
    void failedOrderBecomesTerminalFailureNotSuccessOrRetry() {
        batch(record());
        when(orders.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(order(OrderStatus.FAILED));
        when(messages.markDeadFromConsuming(eq(REQUEST_ID), eq(MessageStatus.DEAD.getCode()),
                eq(MessageStatus.CONSUMING.getCode()), any())).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        job.recoverStaleConsumingMessages();
        verify(values).set(RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID),
                String.valueOf(OrderStatus.FAILED.getCode()), properties.getOrderStatusTtl());
        verify(messages, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
        verify(messages, never()).markFailedFromConsuming(any(), anyInt(), anyInt(), any(), any());
        verify(metrics).mq("consuming_recovered_business_failed");
    }

    @Test
    void unknownOrderStatusDoesNotInventSuccess() {
        batch(record());
        when(orders.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(new SeckillOrder());
        job.recoverStaleConsumingMessages();
        verify(messages, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
        verify(messages, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(redis, never()).opsForValue();
    }

    @Test
    void redisFailureDoesNotPreventNextRecordRecovery() {
        SeckillMessageRecord second = record();
        second.setRequestId("req-002");
        second.setUserId(USER_ID + 1);
        batch(record(), second);
        when(orders.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(order(OrderStatus.SUCCESS));
        when(messages.markConsumedFromConsuming(REQUEST_ID, MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode())).thenReturn(1);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        when(messages.markFailedFromConsuming(eq("req-002"), anyInt(), anyInt(), any(), any())).thenReturn(1);
        job.recoverStaleConsumingMessages();
        verify(metrics).mq("consuming_recovered_consumed");
        verify(metrics).mq("consuming_recovered_retry");
        verify(logs).insertLog("req-002", ACTIVITY_ID, USER_ID + 1, SKU_ID, "CONSUMING_RECOVERED_RETRY");
    }

    @Test
    void lostFailureCasDoesNotWriteFailureProjection() {
        batch(record());
        when(orders.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(order(OrderStatus.FAILED));
        job.recoverStaleConsumingMessages();
        verify(redis, never()).opsForValue();
        verify(metrics, never()).mq(any());
    }

    private void batch(SeckillMessageRecord... records) {
        when(messages.selectStaleConsuming(eq(MessageStatus.CONSUMING.getCode()),
                any(LocalDateTime.class), eq(properties.getConsumingRecovery().getBatchSize())))
                .thenReturn(List.of(records));
    }

    private SeckillOrder order(OrderStatus status) {
        SeckillOrder order = new SeckillOrder();
        order.setStatus(status.getCode());
        return order;
    }

    private SeckillMessageRecord record() {
        SeckillMessageRecord record = new SeckillMessageRecord();
        record.setRequestId(REQUEST_ID);
        record.setActivityId(ACTIVITY_ID);
        record.setUserId(USER_ID);
        record.setSkuId(SKU_ID);
        record.setStatus(MessageStatus.CONSUMING.getCode());
        record.setUpdatedAt(LocalDateTime.now().minusMinutes(5));
        return record;
    }
}
