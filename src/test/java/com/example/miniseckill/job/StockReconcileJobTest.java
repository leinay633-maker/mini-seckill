package com.example.miniseckill.job;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.DistributedLockService;
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
class StockReconcileJobTest {

    @Mock private SkuStockMapper skuStockMapper;
    @Mock private SkuStockSegmentMapper skuStockSegmentMapper;
    @Mock private SeckillOrderMapper seckillOrderMapper;
    @Mock private SeckillMessageMapper seckillMessageMapper;
    @Mock private CompensationRecordMapper compensationRecordMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SoldOutCacheService soldOutCacheService;

    private SeckillProperties properties;
    private StockReconcileJob job;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getMysqlStockSegment().setEnabled(false);
        properties.getStockShard().setEnabled(false);
        job = new StockReconcileJob(
                skuStockMapper,
                skuStockSegmentMapper,
                seckillOrderMapper,
                seckillMessageMapper,
                compensationRecordMapper,
                stringRedisTemplate,
                distributedLockService,
                properties,
                soldOutCacheService
        );
    }

    @Test
    void leavesRedisAloneWhenFactsAlreadyMatch() {
        SkuStock stock = stock(1L, 10, 4);
        when(skuStockMapper.selectPageAfterId(0L, properties.getReconcile().getPageSize()))
                .thenReturn(List.of(stock));
        doAnswer(invocation -> invocation.<java.util.function.Supplier<Object>>getArgument(3).get())
                .when(distributedLockService).executeWithLock(any(), any(Duration.class), any(Duration.class), any());
        when(seckillOrderMapper.countByActivitySkuStatus(1L, 1001L, OrderStatus.SUCCESS.getCode()))
                .thenReturn(4L);
        when(seckillMessageMapper.countRecoveringUnfinishedByActivitySku(
                eq(1L), eq(1001L), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(0L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("seckill:stock:1:1001")).thenReturn("10");

        job.reconcileStock();

        verify(compensationRecordMapper, never()).insert(any());
        verify(stringRedisTemplate, never()).delete(any(String.class));
        verify(valueOperations, never()).set(any(String.class), any(String.class));
    }

    @Test
    void doesNothingWhenReconcileIsDisabled() {
        properties.getReconcile().setEnabled(false);

        job.reconcileStock();

        verify(skuStockMapper, never()).selectPageAfterId(anyLong(), anyInt());
    }

    private SkuStock stock(Long id, int available, int sold) {
        SkuStock stock = new SkuStock();
        stock.setId(id);
        stock.setActivityId(1L);
        stock.setSkuId(1001L);
        stock.setTotalStock(available + sold);
        stock.setAvailableStock(available);
        stock.setSoldCount(sold);
        return stock;
    }
}
