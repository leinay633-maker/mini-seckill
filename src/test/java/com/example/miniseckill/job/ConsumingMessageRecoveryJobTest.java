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

    @Mock
    private SeckillMessageMapper seckillMessageMapper;
    @Mock
    private SeckillOrderMapper seckillOrderMapper;
    @Mock
    private SeckillLogMapper seckillLogMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SeckillMetrics seckillMetrics;

    private SeckillProperties properties;
    private ConsumingMessageRecoveryJob job;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        job = new ConsumingMessageRecoveryJob(
                seckillMessageMapper,
                seckillOrderMapper,
                seckillLogMapper,
                stringRedisTemplate,
                properties,
                seckillMetrics
        );
    }

    @Test
    void recoverStaleConsumingMarksConsumedWhenOrderExists() {
        SeckillMessageRecord record = record();
        when(seckillMessageMapper.selectStaleConsuming(
                eq(MessageStatus.CONSUMING.getCode()),
                any(LocalDateTime.class),
                eq(properties.getConsumingRecovery().getBatchSize())
        )).thenReturn(List.of(record));
        when(seckillOrderMapper.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(new SeckillOrder());
        when(seckillMessageMapper.markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        )).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        job.recoverStaleConsumingMessages();

        verify(valueOperations).set(
                RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID),
                String.valueOf(OrderStatus.SUCCESS.getCode()),
                properties.getOrderStatusTtl()
        );
        verify(seckillLogMapper).insertLog(REQUEST_ID, ACTIVITY_ID, USER_ID, SKU_ID, "CONSUMING_RECOVERED_CONSUMED");
        verify(seckillMetrics).mq("consuming_recovered_consumed");
        verify(seckillMessageMapper, never()).markFailedFromConsuming(any(), anyInt(), anyInt(), any(), any(LocalDateTime.class));
    }

    @Test
    void recoverStaleConsumingMarksFailedForRetryWhenOrderDoesNotExist() {
        SeckillMessageRecord record = record();
        when(seckillMessageMapper.selectStaleConsuming(
                eq(MessageStatus.CONSUMING.getCode()),
                any(LocalDateTime.class),
                eq(properties.getConsumingRecovery().getBatchSize())
        )).thenReturn(List.of(record));
        when(seckillOrderMapper.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(null);
        when(seckillMessageMapper.markFailedFromConsuming(
                eq(REQUEST_ID),
                eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONSUMING.getCode()),
                eq("stale consuming recovered for retry"),
                any(LocalDateTime.class)
        )).thenReturn(1);

        job.recoverStaleConsumingMessages();

        verify(seckillLogMapper).insertLog(REQUEST_ID, ACTIVITY_ID, USER_ID, SKU_ID, "CONSUMING_RECOVERED_RETRY");
        verify(seckillMetrics).mq("consuming_recovered_retry");
        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillMessageMapper, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
    }

    @Test
    void recoverStaleConsumingSkipsSideEffectsWhenConsumedUpdateMisses() {
        SeckillMessageRecord record = record();
        when(seckillMessageMapper.selectStaleConsuming(
                eq(MessageStatus.CONSUMING.getCode()),
                any(LocalDateTime.class),
                eq(properties.getConsumingRecovery().getBatchSize())
        )).thenReturn(List.of(record));
        when(seckillOrderMapper.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(new SeckillOrder());
        when(seckillMessageMapper.markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        )).thenReturn(0);

        job.recoverStaleConsumingMessages();

        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillLogMapper, never()).insertLog(any(), any(), any(), any(), any());
        verify(seckillMetrics, never()).mq(any());
    }

    @Test
    void recoverStaleConsumingDoesNothingWhenDisabled() {
        properties.getConsumingRecovery().setEnabled(false);

        job.recoverStaleConsumingMessages();

        verify(seckillMessageMapper, never()).selectStaleConsuming(anyInt(), any(LocalDateTime.class), anyInt());
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
