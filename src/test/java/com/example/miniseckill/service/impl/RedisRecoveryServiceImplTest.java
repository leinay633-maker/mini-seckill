package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RedisRecoveryResponse;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.DistributedLockService;
import com.example.miniseckill.service.RedisRecoveryStateService;
import com.example.miniseckill.service.SoldOutCacheService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisRecoveryServiceImplTest {

    @Mock private SkuStockMapper skuStockMapper;
    @Mock private SkuStockSegmentMapper skuStockSegmentMapper;
    @Mock private SeckillMessageMapper seckillMessageMapper;
    @Mock private SeckillLogMapper seckillLogMapper;
    @Mock private CompensationRecordMapper compensationRecordMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private DistributedLockService distributedLockService;
    @Mock private RedisRecoveryStateService recoveryStateService;
    @Mock private SoldOutCacheService soldOutCacheService;

    private SeckillProperties properties;
    private RedisRecoveryServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getStockShard().setEnabled(false);
        properties.getMysqlStockSegment().setEnabled(false);
        properties.getAntiBrush().setEnabled(false);
        service = new RedisRecoveryServiceImpl(
                skuStockMapper,
                skuStockSegmentMapper,
                seckillMessageMapper,
                seckillLogMapper,
                compensationRecordMapper,
                stringRedisTemplate,
                distributedLockService,
                properties,
                recoveryStateService,
                soldOutCacheService
        );
        org.mockito.Mockito.lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void rebuildsAvailableStockMinusUnfinishedMessages() {
        SkuStock stock = stock(5);
        when(skuStockMapper.selectRecentStocks(anyInt())).thenReturn(List.of(stock));
        when(seckillMessageMapper.countRecoveringUnfinishedByActivitySku(
                eq(1L), eq(1001L),
                eq(MessageStatus.PENDING.getCode()), eq(MessageStatus.SENDING.getCode()),
                eq(MessageStatus.SENT.getCode()), eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONFIRM_FAILED.getCode()), eq(MessageStatus.RETURNED.getCode()),
                eq(MessageStatus.CONSUMING.getCode())
        )).thenReturn(2L);
        doAnswer(invocation -> invocation.<java.util.function.Supplier<Integer>>getArgument(3).get())
                .when(distributedLockService).executeWithLock(any(), any(Duration.class), any(Duration.class), any());

        RedisRecoveryResponse response = service.recoverRedisStock();

        assertEquals(1, response.getRecoveredSkuCount());
        assertEquals(3, response.getTotalExpectedRedisStock());
        verify(valueOperations).set("seckill:stock:1:1001", "3");
        verify(recoveryStateService).markRecovered();
    }

    @Test
    void clampsExpectedStockAtZeroWhenUnfinishedExceedsAvailable() {
        SkuStock stock = stock(1);
        when(skuStockMapper.selectRecentStocks(anyInt())).thenReturn(List.of(stock));
        when(seckillMessageMapper.countRecoveringUnfinishedByActivitySku(
                anyLong(), anyLong(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()
        )).thenReturn(2L);
        doAnswer(invocation -> invocation.<java.util.function.Supplier<Integer>>getArgument(3).get())
                .when(distributedLockService).executeWithLock(any(), any(Duration.class), any(Duration.class), any());

        RedisRecoveryResponse response = service.recoverRedisStock();

        assertEquals(0, response.getTotalExpectedRedisStock());
        verify(valueOperations).set("seckill:stock:1:1001", "0");
        verify(soldOutCacheService).markSoldOut(1L, 1001L);
    }

    @Test
    void keepsRecoveryModeWhenAnotherInstanceOwnsLock() {
        SkuStock stock = stock(5);
        when(skuStockMapper.selectRecentStocks(anyInt())).thenReturn(List.of(stock));
        doThrow(new BusinessException(409, "lock busy"))
                .when(distributedLockService).executeWithLock(any(), any(Duration.class), any(Duration.class), any());

        BusinessException exception = assertThrows(BusinessException.class, service::recoverRedisStock);

        assertEquals(503, exception.getCode());
        verify(recoveryStateService, never()).markRecovered();
        verify(recoveryStateService, org.mockito.Mockito.times(2)).markRecovering(any());
        verify(valueOperations, never()).set(any(), any());
    }

    private SkuStock stock(int available) {
        SkuStock stock = new SkuStock();
        stock.setId(1L);
        stock.setActivityId(1L);
        stock.setSkuId(1001L);
        stock.setTotalStock(10);
        stock.setAvailableStock(available);
        stock.setSoldCount(10 - available);
        return stock;
    }
}
