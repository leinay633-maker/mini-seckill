package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.SeckillOrder;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.util.RedisKeyUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    private static final long ACTIVITY_ID = 1L;
    private static final long SKU_ID = 1001L;
    private static final long USER_ID = 10007L;
    private static final String REQUEST_ID = "req-001";

    @Mock
    private SeckillOrderMapper seckillOrderMapper;
    @Mock
    private SkuStockMapper skuStockMapper;
    @Mock
    private SeckillLogMapper seckillLogMapper;
    @Mock
    private SeckillMessageMapper seckillMessageMapper;
    @Mock
    private SkuStockSegmentMapper skuStockSegmentMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SeckillMetrics seckillMetrics;

    private SeckillProperties seckillProperties;
    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        seckillProperties = new SeckillProperties();
        seckillProperties.getMysqlStockSegment().setEnabled(true);
        seckillProperties.getMysqlStockSegment().setSegmentCount(4);
        orderService = new OrderServiceImpl(
                seckillOrderMapper,
                skuStockMapper,
                seckillLogMapper,
                seckillMessageMapper,
                skuStockSegmentMapper,
                stringRedisTemplate,
                seckillProperties,
                seckillMetrics
        );
    }

    @Test
    void createOrderFromMessageDeductsPreferredSegmentWhenAvailable() {
        int preferredSegment = preferredSegment(USER_ID);
        stubOrderStatusWrite();
        when(skuStockSegmentMapper.countSegments(ACTIVITY_ID, SKU_ID)).thenReturn(4);
        when(skuStockSegmentMapper.decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment)).thenReturn(1);

        orderService.createOrderFromMessage(message(USER_ID));

        verify(skuStockSegmentMapper).decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment);
        verify(skuStockSegmentMapper, never()).decreaseAnySegmentStock(ACTIVITY_ID, SKU_ID);
        verify(skuStockMapper, never()).decreaseStock(ACTIVITY_ID, SKU_ID);
        verifySuccessfulOrderSideEffects();
    }

    @Test
    void createOrderFromMessageFallsBackToAnySegmentWhenPreferredSegmentIsEmpty() {
        int preferredSegment = preferredSegment(USER_ID);
        stubOrderStatusWrite();
        when(skuStockSegmentMapper.countSegments(ACTIVITY_ID, SKU_ID)).thenReturn(4);
        when(skuStockSegmentMapper.decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment)).thenReturn(0);
        when(skuStockSegmentMapper.decreaseAnySegmentStock(ACTIVITY_ID, SKU_ID)).thenReturn(1);

        orderService.createOrderFromMessage(message(USER_ID));

        verify(skuStockSegmentMapper).decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment);
        verify(skuStockSegmentMapper).decreaseAnySegmentStock(ACTIVITY_ID, SKU_ID);
        verify(skuStockMapper, never()).decreaseStock(ACTIVITY_ID, SKU_ID);
        verifySuccessfulOrderSideEffects();
    }

    @Test
    void createOrderFromMessageFallsBackToMainStockWhenSegmentsAreNotInitialized() {
        stubOrderStatusWrite();
        when(skuStockSegmentMapper.countSegments(ACTIVITY_ID, SKU_ID)).thenReturn(0);
        when(skuStockMapper.decreaseStock(ACTIVITY_ID, SKU_ID)).thenReturn(1);

        orderService.createOrderFromMessage(message(USER_ID));

        verify(skuStockMapper).decreaseStock(ACTIVITY_ID, SKU_ID);
        verify(skuStockSegmentMapper, never()).decreaseSegmentStock(eq(ACTIVITY_ID), eq(SKU_ID), any(Integer.class));
        verify(skuStockSegmentMapper, never()).decreaseAnySegmentStock(ACTIVITY_ID, SKU_ID);
        verifySuccessfulOrderSideEffects();
    }

    @Test
    void createOrderFromConsumingMessageMarksConsumedOnlyFromConsumingStatus() {
        int preferredSegment = preferredSegment(USER_ID);
        stubOrderStatusWrite();
        when(skuStockSegmentMapper.countSegments(ACTIVITY_ID, SKU_ID)).thenReturn(4);
        when(skuStockSegmentMapper.decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment)).thenReturn(1);
        when(seckillMessageMapper.markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        )).thenReturn(1);

        orderService.createOrderFromConsumingMessage(message(USER_ID));

        verify(seckillMessageMapper).markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
        verify(seckillMessageMapper, never()).updateStatus(REQUEST_ID, MessageStatus.CONSUMED.getCode());
        verify(valueOperations).set(
                RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID),
                String.valueOf(OrderStatus.SUCCESS.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
    }

    @Test
    void createOrderFromConsumingMessageThrowsWhenMessageStatusChanged() {
        int preferredSegment = preferredSegment(USER_ID);
        when(skuStockSegmentMapper.countSegments(ACTIVITY_ID, SKU_ID)).thenReturn(4);
        when(skuStockSegmentMapper.decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment)).thenReturn(1);
        when(seckillMessageMapper.markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        )).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> orderService.createOrderFromConsumingMessage(message(USER_ID)));

        verify(valueOperations, never()).set(
                eq(RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID)),
                eq(String.valueOf(OrderStatus.SUCCESS.getCode())),
                eq(seckillProperties.getOrderStatusTtl())
        );
    }

    @Test
    void createOrderFromMessageThrowsWhenMysqlStockGuardRejectsDeduction() {
        when(skuStockSegmentMapper.countSegments(ACTIVITY_ID, SKU_ID)).thenReturn(4);
        when(skuStockSegmentMapper.decreaseSegmentStock(ACTIVITY_ID, SKU_ID, preferredSegment(USER_ID))).thenReturn(0);
        when(skuStockSegmentMapper.decreaseAnySegmentStock(ACTIVITY_ID, SKU_ID)).thenReturn(0);

        assertThrows(InsufficientStockException.class, () -> orderService.createOrderFromMessage(message(USER_ID)));

        verify(seckillMetrics).order("mysql_stock_guard_failed");
        verify(seckillMessageMapper, never()).updateStatus(REQUEST_ID, MessageStatus.CONSUMED.getCode());
        verify(valueOperations, never()).set(
                eq(RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID)),
                eq(String.valueOf(OrderStatus.SUCCESS.getCode())),
                eq(seckillProperties.getOrderStatusTtl())
        );
    }

    private SeckillMessage message(long userId) {
        return new SeckillMessage(REQUEST_ID, ACTIVITY_ID, userId, SKU_ID, 1_717_000_000_000L);
    }

    private int preferredSegment(long userId) {
        return Math.floorMod(Long.valueOf(userId).hashCode(), seckillProperties.getMysqlStockSegment().getSegmentCount());
    }

    private void stubOrderStatusWrite() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    private void verifySuccessfulOrderSideEffects() {
        verify(seckillOrderMapper).insert(any(SeckillOrder.class));
        verify(seckillLogMapper).insertLog(REQUEST_ID, ACTIVITY_ID, USER_ID, SKU_ID, "ORDER_SUCCESS");
        verify(seckillMessageMapper).updateStatus(REQUEST_ID, MessageStatus.CONSUMED.getCode());
        verify(valueOperations).set(
                RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID),
                String.valueOf(OrderStatus.SUCCESS.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
        verify(seckillMetrics).order("success");
    }
}
