package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.miniseckill.service.OrderIdGenerator;
import com.example.miniseckill.service.SeckillMetrics;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Callback unit tests; actual commit/rollback visibility is covered by OrderCommitBoundaryIT. */
@ExtendWith(MockitoExtension.class)
class OrderCommitBoundaryTest {
    @Mock private SeckillOrderMapper orders;
    @Mock private SkuStockMapper stocks;
    @Mock private SeckillLogMapper logs;
    @Mock private SeckillMessageMapper messages;
    @Mock private SkuStockSegmentMapper segments;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private SeckillMetrics metrics;
    @Mock private OrderIdGenerator ids;
    private OrderServiceImpl service;
    private final SeckillMessage message = new SeckillMessage("boundary", 1L, 10L, 1001L, 0L);

    @BeforeEach
    void setUp() {
        SeckillProperties properties = new SeckillProperties();
        properties.getMysqlStockSegment().setEnabled(false);
        service = new OrderServiceImpl(orders, stocks, logs, messages, segments, redis, properties, metrics, ids);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void successProjectionAndSuccessMetricWaitForCommit() {
        successfulSql();
        TransactionSynchronizationManager.initSynchronization();
        service.createOrderFromConsumingMessage(message);
        verify(redis, never()).opsForValue();
        verify(metrics, never()).order("success");
        when(redis.opsForValue()).thenReturn(values);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(values).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
        verify(metrics).order("success");
    }

    @Test
    void rollbackCompletionDoesNotPublishSuccess() {
        successfulSql();
        TransactionSynchronizationManager.initSynchronization();
        service.createOrderFromConsumingMessage(message);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(redis, never()).opsForValue();
        verify(metrics, never()).order("success");
    }

    @Test
    void redisFailureAfterCommitDoesNotEscapeAsBusinessFailure() {
        successfulSql();
        TransactionSynchronizationManager.initSynchronization();
        service.createOrderFromConsumingMessage(message);
        when(redis.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        assertDoesNotThrow(() -> TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit));
        verify(metrics).order("status_cache_write_failed");
        verify(metrics).order("success");
    }

    @Test
    void duplicateSuccessUsesBusinessFactWithoutStockDeduction() {
        when(orders.selectByUserSku(1L, 10L, 1001L)).thenReturn(order(OrderStatus.SUCCESS));
        when(messages.markConsumedFromConsuming("boundary", MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode())).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        service.reconcileExistingOrderFromConsumingMessage(message);
        verify(stocks, never()).decreaseStock(any(), any());
        verify(orders, never()).insert(any());
        verify(values).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
        verify(metrics, never()).order("success");
    }

    @Test
    void duplicateFailedOrderIsTerminalAndNeverBecomesSuccess() {
        when(orders.selectByUserSku(1L, 10L, 1001L)).thenReturn(order(OrderStatus.FAILED));
        when(messages.markDeadFromConsuming(eq("boundary"), eq(MessageStatus.DEAD.getCode()),
                eq(MessageStatus.CONSUMING.getCode()), any())).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        service.reconcileExistingOrderFromConsumingMessage(message);
        verify(messages, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
        verify(values).set(any(), eq(String.valueOf(OrderStatus.FAILED.getCode())), any(Duration.class));
        verify(stocks, never()).decreaseStock(any(), any());
    }

    @Test
    void orderIdCollisionWithoutBusinessOrderIsNotSuccess() {
        assertThrows(IllegalStateException.class, () -> service.reconcileExistingOrderFromConsumingMessage(message));
        verify(messages, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
        verify(redis, never()).opsForValue();
    }

    @Test
    void unsupportedDurableStatusIsNotSuccess() {
        SeckillOrder order = new SeckillOrder();
        order.setStatus(99);
        when(orders.selectByUserSku(1L, 10L, 1001L)).thenReturn(order);
        assertThrows(IllegalStateException.class, () -> service.reconcileExistingOrderFromConsumingMessage(message));
        verify(messages, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
        verify(redis, never()).opsForValue();
    }

    @Test
    void failedOrderPersistenceErrorsPropagateInsteadOfPretendingToBeDurable() {
        doThrow(new TransientDataAccessResourceException("database down")).when(orders).insert(any());
        assertThrows(TransientDataAccessResourceException.class, () -> service.recordFailedOrder(message));
        verify(messages, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(redis, never()).opsForValue();
    }

    @Test
    void failedOrderInsertCollisionReconcilesActualSuccess() {
        doThrow(new DuplicateKeyException("business key")).when(orders).insert(any());
        when(orders.selectByUserSku(1L, 10L, 1001L)).thenReturn(order(OrderStatus.SUCCESS));
        when(messages.markConsumedFromConsuming("boundary", MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode())).thenReturn(1);
        when(redis.opsForValue()).thenReturn(values);
        service.recordFailedOrder(message);
        verify(values).set(any(), eq(String.valueOf(OrderStatus.SUCCESS.getCode())), any(Duration.class));
        verify(messages, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
    }

    @Test
    void failedCompletionCasMissHasNoProjection() {
        assertThrows(IllegalStateException.class, () -> service.recordFailedOrder(message));
        verify(redis, never()).opsForValue();
    }

    private void successfulSql() {
        when(stocks.decreaseStock(1L, 1001L)).thenReturn(1);
        when(messages.markConsumedFromConsuming("boundary", MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode())).thenReturn(1);
    }

    private SeckillOrder order(OrderStatus status) {
        SeckillOrder order = new SeckillOrder();
        order.setStatus(status.getCode());
        return order;
    }
}
