package com.example.miniseckill.mq;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.SeckillMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;

@ExtendWith(MockitoExtension.class)
class SeckillConsumerTest {

    private static final String REQUEST_ID = "req-001";
    private static final long DELIVERY_TAG = 42L;

    @Mock private OrderService orderService;
    @Mock private SeckillLogMapper seckillLogMapper;
    @Mock private SeckillMessageMapper seckillMessageMapper;
    @Mock private CompensationRecordMapper compensationRecordMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private SeckillMetrics seckillMetrics;
    @Mock private Channel channel;

    private SeckillConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SeckillConsumer(orderService, seckillLogMapper, seckillMessageMapper,
                compensationRecordMapper, stringRedisTemplate, new SeckillProperties(), seckillMetrics);
    }

    @Test
    void consumeSkipsMessageWhenStatusIsNoLongerConsumable() throws Exception {
        SeckillMessage message = message();
        consumer.consume(message, rawMessage(), channel);
        verify(orderService, never()).createOrderFromConsumingMessage(message);
        verify(seckillMetrics).mq("consume_skipped");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consumeMarksConsumingBeforeCreatingOrderAndAcksAfterServiceReturns() throws Exception {
        claim();
        SeckillMessage message = message();
        consumer.consume(message, rawMessage(), channel);
        InOrder order = inOrder(seckillMessageMapper, orderService, channel);
        order.verify(seckillMessageMapper).markConsuming(REQUEST_ID, MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(), MessageStatus.SENDING.getCode(), MessageStatus.REPLAYED.getCode());
        order.verify(orderService).createOrderFromConsumingMessage(message);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(seckillMetrics).mq("consume_success");
    }

    @Test
    void duplicateConsumeReconcilesDurableFactBeforeAcking() throws Exception {
        claim();
        SeckillMessage message = message();
        doThrow(new DuplicateKeyException("duplicate"))
                .when(orderService).createOrderFromConsumingMessage(message);
        consumer.consume(message, rawMessage(), channel);
        InOrder order = inOrder(orderService, channel);
        order.verify(orderService).createOrderFromConsumingMessage(message);
        order.verify(orderService).reconcileExistingOrderFromConsumingMessage(message);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(seckillMessageMapper, never()).markConsumedFromConsuming(any(), anyInt(), anyInt());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillMetrics).mq("duplicate_acked");
    }

    @Test
    void duplicateWithoutBusinessFactIsNotAcknowledgedAsSuccess() throws Exception {
        claim();
        SeckillMessage message = message();
        doThrow(new DuplicateKeyException("order_id collision"))
                .when(orderService).createOrderFromConsumingMessage(message);
        doThrow(new IllegalStateException("missing business order"))
                .when(orderService).reconcileExistingOrderFromConsumingMessage(message);
        assertThrows(IllegalStateException.class, () -> consumer.consume(message, rawMessage(), channel));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillMetrics, never()).mq("consume_success");
    }

    @Test
    void transientDataAccessFailureReturnsMessageToRetryStateAndAcks() throws Exception {
        claim();
        SeckillMessage message = message();
        doThrow(new TransientDataAccessResourceException("temporary database failure"))
                .when(orderService).createOrderFromConsumingMessage(message);
        when(seckillMessageMapper.markFailedFromConsuming(eq(REQUEST_ID), eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONSUMING.getCode()), eq("temporary database failure"), any())).thenReturn(1);
        consumer.consume(message, rawMessage(), channel);
        verify(seckillMetrics).mq("transient_requeued");
        verify(seckillMessageMapper, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(compensationRecordMapper, never()).insert(any());
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void lostRetryCasDoesNotDeadLetterOrOverwriteNewerState() throws Exception {
        claim();
        doThrow(new TransientDataAccessResourceException("temporary database failure"))
                .when(orderService).createOrderFromConsumingMessage(any());
        consumer.consume(message(), rawMessage(), channel);
        verify(seckillMessageMapper, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(compensationRecordMapper, never()).insert(any());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillMetrics).mq("consume_state_changed");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void insufficientStockPersistsFailureAndTerminalMessageBeforeAcking() throws Exception {
        claim();
        SeckillMessage message = message();
        doThrow(new InsufficientStockException("stock exhausted"))
                .when(orderService).createOrderFromConsumingMessage(message);
        consumer.consume(message, rawMessage(), channel);
        InOrder order = inOrder(orderService, channel);
        order.verify(orderService).createOrderFromConsumingMessage(message);
        order.verify(orderService).recordFailedOrder(message);
        order.verify(channel).basicAck(DELIVERY_TAG, false);
        verify(seckillMessageMapper, never()).markFailed(any(), anyInt(), any());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(seckillMetrics).mq("stock_guard_failed");
    }

    @Test
    void failedOrderPersistenceErrorMustNotBeAcknowledged() throws Exception {
        claim();
        SeckillMessage message = message();
        doThrow(new InsufficientStockException("stock exhausted"))
                .when(orderService).createOrderFromConsumingMessage(message);
        doThrow(new TransientDataAccessResourceException("database unavailable"))
                .when(orderService).recordFailedOrder(message);
        assertThrows(TransientDataAccessResourceException.class, () -> consumer.consume(message, rawMessage(), channel));
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(compensationRecordMapper, never()).insert(any());
    }

    @Test
    void ackIoFailureAfterCommitDoesNotTriggerBusinessFailureOrSecondSettlement() throws Exception {
        claim();
        doThrow(new IOException("connection closed after commit")).when(channel).basicAck(DELIVERY_TAG, false);
        assertThrows(IOException.class, () -> consumer.consume(message(), rawMessage(), channel));
        verify(seckillMessageMapper, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(seckillMessageMapper, never()).markFailed(any(), anyInt(), any());
        verify(orderService, never()).recordFailedOrder(any());
        verify(compensationRecordMapper, never()).insert(any());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ackRuntimeFailureIsAlsoOutsideBusinessFailureHandler() throws Exception {
        claim();
        doThrow(new IllegalStateException("channel closed")).when(channel).basicAck(DELIVERY_TAG, false);
        assertThrows(IllegalStateException.class, () -> consumer.consume(message(), rawMessage(), channel));
        verify(seckillMessageMapper, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void fatalErrorOnlyWritesFailureProjectionWhenStateTransitionWins() throws Exception {
        claim();
        doThrow(new IllegalArgumentException("bad payload")).when(orderService).createOrderFromConsumingMessage(any());
        consumer.consume(message(), rawMessage(), channel);
        verify(compensationRecordMapper, never()).insert(any());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void metricsFailureCannotTurnCommittedSuccessIntoDeadLetter() throws Exception {
        claim();
        doThrow(new IllegalStateException("metrics unavailable")).when(seckillMetrics).mq("consume_success");
        consumer.consume(message(), rawMessage(), channel);
        verify(seckillMessageMapper, never()).markDeadFromConsuming(any(), anyInt(), anyInt(), any());
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    private void claim() {
        when(seckillMessageMapper.markConsuming(REQUEST_ID, MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(), MessageStatus.SENDING.getCode(), MessageStatus.REPLAYED.getCode())).thenReturn(1);
    }

    private SeckillMessage message() {
        return new SeckillMessage(REQUEST_ID, 1L, 10007L, 1001L, 1_717_000_000_000L);
    }

    private Message rawMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        return new Message(new byte[0], properties);
    }
}
