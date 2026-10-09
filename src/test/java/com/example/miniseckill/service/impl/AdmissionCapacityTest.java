package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.*;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.entity.SeckillActivity;
import com.example.miniseckill.entity.SeckillOrder;
import com.example.miniseckill.mapper.SeckillActivityMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.support.AdmissionHarness;
import com.example.miniseckill.util.RedisKeyUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;

class AdmissionCapacityTest {
    private final SeckillMessageMapper messages = mock(SeckillMessageMapper.class);
    private final SeckillOrderMapper orders = mock(SeckillOrderMapper.class);
    private final SeckillActivityMapper activities = mock(SeckillActivityMapper.class);
    private final AdmissionCapacityProperties capacity = new AdmissionCapacityProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeckillMetrics metrics = new SeckillMetrics(registry);
    private ActivityServiceImpl activityService;
    private AdmissionHarness harness;

    @BeforeEach
    void setUp() {
        SeckillActivity row = new SeckillActivity();
        row.setActivityId(1L);
        row.setStatus(ActivityStatus.RUNNING.getCode());
        row.setStartTime(LocalDateTime.now().minusHours(1));
        row.setEndTime(LocalDateTime.now().plusHours(1));
        when(activities.selectByActivityId(1L)).thenReturn(row);
        when(messages.insertPending(anyString(), anyLong(), anyLong(), anyLong(), anyInt())).thenReturn(1);
        rebuild();
    }

    private void rebuild() {
        activityService = new ActivityServiceImpl(activities, capacity, metrics, () -> 0L, Clock.systemDefaultZone());
        harness = new AdmissionHarness(messages, orders, activityService, capacity, metrics);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fixedWorkloadCountsSqlCallsForBothIndependentAblations(boolean cache, boolean initialSending) {
        capacity.setActivityCacheTtl(cache ? Duration.ofMillis(250) : Duration.ZERO);
        capacity.setInitialSendingEnabled(initialSending);
        rebuild();
        // A deterministic call-count check, deliberately NOT a throughput test.
        int users = 12;
        for (long user = 1; user <= users; user++) {
            assertEquals(0, harness.service.createOrderToken(1L, user, 1001L, "test").getCode());
            assertEquals(0, harness.service.placeOrder(harness.request(user), "test").getCode());
        }
        verify(activities, times(cache ? 1 : users * 2)).selectByActivityId(1L);
        verify(orders, times(users)).selectByUserSku(eq(1L), anyLong(), eq(1001L));
        verify(messages, times(users)).insertPending(anyString(), eq(1L), anyLong(), eq(1001L),
                eq(initialSending ? MessageStatus.SENDING.getCode() : MessageStatus.PENDING.getCode()));
        verify(messages, times(initialSending ? 0 : users)).markSending(anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertEquals(users, registry.get("seckill_capacity_stage").tag("stage", "token_order_lookup").timer().count());
        assertEquals(users, registry.get("seckill_capacity_stage").tag("stage", "message_insert").timer().count());
        assertEquals(users, registry.get("seckill_capacity_stage").tag("stage", "initial_publish").timer().count());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"SUCCESS", "FAILED"})
    void redisMissStillChecksMysqlAndRejectsPersistedBusinessOrders(OrderStatus status) {
        SeckillOrder order = new SeckillOrder();
        order.setStatus(status.getCode());
        when(orders.selectByUserSku(1L, 10L, 1001L)).thenReturn(order);
        assertEquals(409, harness.service.createOrderToken(1L, 10L, 1001L, "test").getCode());
        verify(harness.values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verifyNoInteractions(harness.rabbit);
    }

    @Test
    void mysqlLookupFailureDoesNotIssueATokenOrTurnIntoANegativeCacheHit() {
        when(orders.selectByUserSku(1L, 10L, 1001L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> harness.service.createOrderToken(1L, 10L, 1001L, "test"));
        assertThrows(IllegalStateException.class, () -> harness.service.createOrderToken(1L, 10L, 1001L, "test"));
        verify(orders, times(2)).selectByUserSku(1L, 10L, 1001L);
        verify(harness.values, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisRecoveryGateStillRunsBeforeTheCachedActivityCheck() {
        activityService.assertRunning(1L);
        when(harness.recovery.isRecovering()).thenReturn(true);
        assertEquals(503, assertThrows(BusinessException.class,
                () -> harness.service.createOrderToken(1L, 10L, 1001L, "test")).getCode());
        assertEquals(503, assertThrows(BusinessException.class,
                () -> harness.service.placeOrder(harness.request(10L), "test")).getCode());
        verifyNoInteractions(orders, harness.rabbit);
    }

    @Test
    void redisPositiveDuplicateDoesNotNeedTheMysqlFallback() {
        when(harness.values.get(RedisKeyUtil.orderStatusKey(1L, 10L, 1001L)))
                .thenReturn(String.valueOf(OrderStatus.SUCCESS.getCode()));
        assertEquals(409, harness.service.createOrderToken(1L, 10L, 1001L, "test").getCode());
        verifyNoInteractions(orders);
    }

    @Test
    void knownInsertRejectionDoesNotPublishAndKeepsExistingCompensationPath() {
        when(messages.insertPending(anyString(), anyLong(), anyLong(), anyLong(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("injected rejection before INSERT"));
        assertThrows(DataIntegrityViolationException.class, () -> harness.service.placeOrder(harness.request(10L), "test"));
        verifyNoInteractions(harness.rabbit);
        verify(harness.values).increment(RedisKeyUtil.stockKey(1L, 1001L));
        verify(harness.redis).delete(RedisKeyUtil.userSkuKey(1L, 10L, 1001L));
    }

    @Test
    void zeroInsertedRowsCannotPublishAnUnpersistedMessage() {
        when(messages.insertPending(anyString(), anyLong(), anyLong(), anyLong(), anyInt())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> harness.service.placeOrder(harness.request(10L), "test"));
        verifyNoInteractions(harness.rabbit);
    }

    @Test
    void diagnosticTimerFailureCannotEscapeIntoAdmissionCompensation() {
        io.micrometer.core.instrument.MeterRegistry failing = mock(io.micrometer.core.instrument.MeterRegistry.class);
        when(failing.timer(anyString(), any(String[].class))).thenThrow(new IllegalStateException("registry failed"));
        SeckillMetrics diagnostic = new SeckillMetrics(failing);
        assertDoesNotThrow(() -> diagnostic.capacityStage(SeckillMetrics.CapacityStage.MESSAGE_INSERT, 100));
        metrics.capacityStage(SeckillMetrics.CapacityStage.MESSAGE_INSERT, 123);
        assertEquals(123, registry.get("seckill_capacity_stage").tag("stage", "message_insert")
                .timer().totalTime(TimeUnit.NANOSECONDS), 0.001);
    }
}
