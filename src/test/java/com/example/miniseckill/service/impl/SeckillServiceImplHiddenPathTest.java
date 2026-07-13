package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.dto.TokenResponse;
import com.example.miniseckill.entity.SeckillOrder;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.ActivityService;
import com.example.miniseckill.service.AsyncSeckillLogWriter;
import com.example.miniseckill.service.DistributedLockService;
import com.example.miniseckill.service.DynamicRateLimitService;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.RedisRecoveryStateService;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.service.SoldOutCacheService;
import com.example.miniseckill.util.RedisKeyUtil;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@ExtendWith(MockitoExtension.class)
class SeckillServiceImplHiddenPathTest {

    private static final long ACTIVITY_ID = 1L;
    private static final long USER_ID = 10001L;
    private static final long SKU_ID = 1001L;

    @Mock
    private SkuStockMapper skuStockMapper;
    @Mock
    private SkuStockSegmentMapper skuStockSegmentMapper;
    @Mock
    private SeckillLogMapper seckillLogMapper;
    @Mock
    private SeckillMessageMapper seckillMessageMapper;
    @Mock
    private SeckillOrderMapper seckillOrderMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private DefaultRedisScript<Long> seckillStockScript;
    @Mock
    private DefaultRedisScript<Long> seckillStockShardedScript;
    @Mock
    private DefaultRedisScript<Long> rateLimitScript;
    @Mock
    private DefaultRedisScript<Long> rateLimitSlidingScript;
    @Mock
    private DefaultRedisScript<Long> compareAndDeleteScript;
    @Mock
    private SeckillProducer seckillProducer;
    @Mock
    private OrderService orderService;
    @Mock
    private ActivityService activityService;
    @Mock
    private DistributedLockService distributedLockService;
    @Mock
    private RedisRecoveryStateService redisRecoveryStateService;
    @Mock
    private DynamicRateLimitService dynamicRateLimitService;
    @Mock
    private SoldOutCacheService soldOutCacheService;
    @Mock
    private SeckillMetrics seckillMetrics;
    @Mock
    private AsyncSeckillLogWriter asyncSeckillLogWriter;

    private SeckillProperties properties;
    private SeckillServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        properties.getAntiBrush().setTokenQuotaEnabled(false);
        properties.getAntiBrush().setTokenTtl(Duration.ofMinutes(2));
        service = new SeckillServiceImpl(
                skuStockMapper,
                skuStockSegmentMapper,
                seckillLogMapper,
                seckillMessageMapper,
                seckillOrderMapper,
                stringRedisTemplate,
                seckillStockScript,
                seckillStockShardedScript,
                rateLimitScript,
                rateLimitSlidingScript,
                compareAndDeleteScript,
                seckillProducer,
                orderService,
                activityService,
                properties,
                distributedLockService,
                redisRecoveryStateService,
                dynamicRateLimitService,
                soldOutCacheService,
                seckillMetrics,
                asyncSeckillLogWriter
        );
    }

    @Test
    void createOrderTokenReturnsHiddenOrderPathWhenEnabled() {
        when(redisRecoveryStateService.isRecovering()).thenReturn(false);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyUtil.orderStatusKey(ACTIVITY_ID, USER_ID, SKU_ID))).thenReturn(null);
        when(stringRedisTemplate.hasKey(RedisKeyUtil.userSkuKey(ACTIVITY_ID, USER_ID, SKU_ID))).thenReturn(false);
        when(soldOutCacheService.isSoldOut(ACTIVITY_ID, SKU_ID)).thenReturn(false);
        when(dynamicRateLimitService.effectivePlan(ACTIVITY_ID, SKU_ID))
                .thenReturn(new RateLimitPlan(false, Duration.ofSeconds(1), 0, 0, 0, "test"));
        when(valueOperations.get(RedisKeyUtil.tokenKey(ACTIVITY_ID, USER_ID, SKU_ID))).thenReturn(null);
        when(valueOperations.get(RedisKeyUtil.orderPathKey(ACTIVITY_ID, USER_ID, SKU_ID))).thenReturn(null);
        when(valueOperations.setIfAbsent(eq(RedisKeyUtil.tokenKey(ACTIVITY_ID, USER_ID, SKU_ID)),
                anyString(), eq(properties.getAntiBrush().getTokenTtl()))).thenReturn(true);
        when(valueOperations.setIfAbsent(eq(RedisKeyUtil.orderPathKey(ACTIVITY_ID, USER_ID, SKU_ID)),
                anyString(), eq(properties.getAntiBrush().getTokenTtl()))).thenReturn(true);

        Result<TokenResponse> result = service.createOrderToken(ACTIVITY_ID, USER_ID, SKU_ID, "127.0.0.1");

        assertEquals(0, result.getCode());
        assertNotNull(result.getData().getToken());
        assertNotNull(result.getData().getOrderPath());
        assertEquals(120L, result.getData().getExpiresInSeconds());
    }

    @Test
    void createOrderTokenDoesNotConsumeQuotaWhenOrderAlreadyExists() {
        when(redisRecoveryStateService.isRecovering()).thenReturn(false);
        // D3 makes the duplicate check Redis-first: order-status and idempotency key miss,
        // so it falls through to the authoritative MySQL order lookup, which hits here.
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(seckillOrderMapper.selectByUserSku(ACTIVITY_ID, USER_ID, SKU_ID)).thenReturn(new SeckillOrder());

        Result<TokenResponse> result = service.createOrderToken(ACTIVITY_ID, USER_ID, SKU_ID, "127.0.0.1");

        assertEquals(409, result.getCode());
        verify(stringRedisTemplate, never()).execute(eq(seckillStockScript), anyList());
        verify(valueOperations, never()).setIfAbsent(
                eq(RedisKeyUtil.tokenKey(ACTIVITY_ID, USER_ID, SKU_ID)),
                anyString(),
                eq(properties.getAntiBrush().getTokenTtl())
        );
    }

    @Test
    void placeOrderWithPathRejectsWrongHiddenPathBeforeAdmission() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyUtil.orderPathKey(ACTIVITY_ID, USER_ID, SKU_ID))).thenReturn("expected-path");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.placeOrderWithPath("wrong-path", orderRequest(), "127.0.0.1"));

        assertEquals(403, ex.getCode());
        verify(redisRecoveryStateService, never()).isRecovering();
        verify(seckillProducer, never()).send(any());
    }

    @Test
    void placeOrderRejectsTokenWhenCompareAndDeleteScriptDoesNotConsumeIt() {
        when(redisRecoveryStateService.isRecovering()).thenReturn(false);
        when(soldOutCacheService.isSoldOut(ACTIVITY_ID, SKU_ID)).thenReturn(false);
        when(dynamicRateLimitService.effectivePlan(ACTIVITY_ID, SKU_ID))
                .thenReturn(new RateLimitPlan(false, Duration.ofSeconds(1), 0, 0, 0, "test"));
        when(stringRedisTemplate.execute(
                eq(compareAndDeleteScript),
                eq(Collections.singletonList(RedisKeyUtil.tokenKey(ACTIVITY_ID, USER_ID, SKU_ID))),
                eq("token")
        )).thenReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.placeOrder(orderRequest(), "127.0.0.1"));

        assertEquals(403, ex.getCode());
        verify(stringRedisTemplate, never()).delete(RedisKeyUtil.tokenKey(ACTIVITY_ID, USER_ID, SKU_ID));
        verify(seckillProducer, never()).send(any());
    }

    @Test
    void placeOrderUsesBucketStockAsFactWithoutDecrementingTotalStockKey() {
        when(redisRecoveryStateService.isRecovering()).thenReturn(false);
        when(soldOutCacheService.isSoldOut(ACTIVITY_ID, SKU_ID)).thenReturn(false);
        when(dynamicRateLimitService.effectivePlan(ACTIVITY_ID, SKU_ID))
                .thenReturn(new RateLimitPlan(false, Duration.ofSeconds(1), 0, 0, 0, "test"));
        when(stringRedisTemplate.execute(
                eq(compareAndDeleteScript),
                eq(Collections.singletonList(RedisKeyUtil.tokenKey(ACTIVITY_ID, USER_ID, SKU_ID))),
                eq("token")
        )).thenReturn(1L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                eq(RedisKeyUtil.userSkuKey(ACTIVITY_ID, USER_ID, SKU_ID)),
                anyString(),
                eq(properties.getIdempotentTtl())
        )).thenReturn(true);
        // Default sharded path is now the single-Lua bucket scan (A3): one call, returns the hit bucket index.
        when(stringRedisTemplate.execute(eq(seckillStockShardedScript), anyList(), any())).thenReturn(0L);
        when(seckillMessageMapper.insertPending(
                anyString(),
                eq(ACTIVITY_ID),
                eq(USER_ID),
                eq(SKU_ID),
                eq(0)
        )).thenReturn(1);

        Result<Void> result = service.placeOrder(orderRequest(), "127.0.0.1");

        assertEquals(0, result.getCode());
        verify(valueOperations, never()).decrement(RedisKeyUtil.stockKey(ACTIVITY_ID, SKU_ID));
        verify(seckillProducer).send(any());
    }

    private SeckillOrderRequest orderRequest() {
        SeckillOrderRequest request = new SeckillOrderRequest();
        request.setActivityId(ACTIVITY_ID);
        request.setUserId(USER_ID);
        request.setSkuId(SKU_ID);
        request.setToken("token");
        return request;
    }
}
