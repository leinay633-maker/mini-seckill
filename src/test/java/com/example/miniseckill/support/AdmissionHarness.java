package com.example.miniseckill.support;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.*;
import com.example.miniseckill.service.impl.SeckillServiceImpl;
import java.time.Duration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Shared admission wiring. Only Redis/MQ are doubles; callers choose mock or real SQL mappers. */
public final class AdmissionHarness {
    public final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    public final ValueOperations<String, String> values = mock(ValueOperations.class);
    public final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    public final RedisRecoveryStateService recovery = mock(RedisRecoveryStateService.class);
    public final SeckillProperties properties = new SeckillProperties();
    public final DefaultRedisScript<Long> stockScript = new DefaultRedisScript<>();
    public final InventoryCoordinator inventory = mock(InventoryCoordinator.class);
    public final SeckillProducer producer;
    public final SeckillServiceImpl service;

    public AdmissionHarness(SeckillMessageMapper messages, SeckillOrderMapper orders,
                            ActivityService activities, AdmissionCapacityProperties capacity, SeckillMetrics metrics) {
        properties.getAntiBrush().setEnabled(false);
        properties.getStockShard().setEnabled(false);
        properties.setMqFallbackSync(false);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        lenient().when(redis.execute(eq(stockScript), anyList())).thenReturn(1L);
        lenient().when(inventory.reserve(anyLong(),anyLong(),anyLong(),anyString())).thenReturn(1L);
        DynamicRateLimitService limits = mock(DynamicRateLimitService.class);
        when(limits.effectivePlan(anyLong(), anyLong()))
                .thenReturn(new RateLimitPlan(false, Duration.ofSeconds(1), 0, 0, 0, "test"));
        producer = new SeckillProducer(rabbit, messages, capacity);
        service = new SeckillServiceImpl(mock(SkuStockMapper.class), mock(SkuStockSegmentMapper.class),
                mock(SeckillLogMapper.class), messages, orders, redis, stockScript, new DefaultRedisScript<>(),
                new DefaultRedisScript<>(), new DefaultRedisScript<>(), new DefaultRedisScript<>(),
                producer, mock(OrderService.class), activities, properties, mock(DistributedLockService.class),
                recovery, limits, mock(SoldOutCacheService.class), metrics, mock(AsyncSeckillLogWriter.class), inventory, mock(StockInitialization.class));
    }

    public SeckillOrderRequest request(long userId) {
        SeckillOrderRequest request = new SeckillOrderRequest();
        request.setActivityId(1L);
        request.setUserId(userId);
        request.setSkuId(1001L);
        request.setToken("unused-when-anti-brush-is-disabled");
        return request;
    }
}
