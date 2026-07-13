package com.example.miniseckill.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutJobTest {

    private static final String REQUEST_ID = "req-001";
    private static final long ACTIVITY_ID = 1L;
    private static final long USER_ID = 10001L;
    private static final long SKU_ID = 1001L;

    @Mock
    private SeckillMessageMapper seckillMessageMapper;
    @Mock
    private SeckillLogMapper seckillLogMapper;
    @Mock
    private CompensationRecordMapper compensationRecordMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SeckillProperties properties;
    private OrderTimeoutJob job;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        job = new OrderTimeoutJob(
                seckillMessageMapper,
                seckillLogMapper,
                compensationRecordMapper,
                stringRedisTemplate,
                properties
        );
    }

    @Test
    void closeTimeoutOrdersWritesSideEffectsOnlyWhenStatusUpdated() {
        SeckillMessageRecord record = record();
        when(seckillMessageMapper.selectTimeoutCandidates(
                eq(MessageStatus.PENDING.getCode()),
                eq(MessageStatus.SENDING.getCode()),
                eq(MessageStatus.SENT.getCode()),
                eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONFIRM_FAILED.getCode()),
                eq(MessageStatus.RETURNED.getCode()),
                any(LocalDateTime.class),
                eq(properties.getOrderTimeout().getBatchSize())
        )).thenReturn(List.of(record));
        when(seckillMessageMapper.markTimeout(
                REQUEST_ID,
                MessageStatus.TIMEOUT.getCode(),
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                "queued order timeout"
        )).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        job.closeTimeoutOrders();

        verify(valueOperations).set(
                RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID),
                String.valueOf(OrderStatus.TIMEOUT.getCode()),
                properties.getOrderStatusTtl()
        );
        verify(stringRedisTemplate).delete(RedisKeyUtil.userSkuKey(ACTIVITY_ID, USER_ID, SKU_ID));
        verify(seckillLogMapper).insertLog(REQUEST_ID, ACTIVITY_ID, USER_ID, SKU_ID, "ORDER_TIMEOUT");
        verify(compensationRecordMapper).insert(any(CompensationRecord.class));
    }

    @Test
    void closeTimeoutOrdersSkipsSideEffectsWhenStatusUpdateMisses() {
        SeckillMessageRecord record = record();
        when(seckillMessageMapper.selectTimeoutCandidates(
                eq(MessageStatus.PENDING.getCode()),
                eq(MessageStatus.SENDING.getCode()),
                eq(MessageStatus.SENT.getCode()),
                eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONFIRM_FAILED.getCode()),
                eq(MessageStatus.RETURNED.getCode()),
                any(LocalDateTime.class),
                eq(properties.getOrderTimeout().getBatchSize())
        )).thenReturn(List.of(record));
        when(seckillMessageMapper.markTimeout(
                REQUEST_ID,
                MessageStatus.TIMEOUT.getCode(),
                MessageStatus.PENDING.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.FAILED.getCode(),
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.RETURNED.getCode(),
                "queued order timeout"
        )).thenReturn(0);

        job.closeTimeoutOrders();

        verify(stringRedisTemplate, never()).opsForValue();
        verify(stringRedisTemplate, never()).delete(any(String.class));
        verify(seckillLogMapper, never()).insertLog(any(), any(), any(), any(), any());
        verify(compensationRecordMapper, never()).insert(any(CompensationRecord.class));
    }

    private SeckillMessageRecord record() {
        SeckillMessageRecord record = new SeckillMessageRecord();
        record.setRequestId(REQUEST_ID);
        record.setActivityId(ACTIVITY_ID);
        record.setUserId(USER_ID);
        record.setSkuId(SKU_ID);
        record.setStatus(MessageStatus.SENT.getCode());
        record.setUpdatedAt(LocalDateTime.now().minusMinutes(30));
        return record;
    }
}
