package com.example.miniseckill.mq;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class SeckillConsumerTest {

    private static final String REQUEST_ID = "req-001";
    private static final long DELIVERY_TAG = 42L;

    @Mock
    private OrderService orderService;
    @Mock
    private SeckillLogMapper seckillLogMapper;
    @Mock
    private SeckillMessageMapper seckillMessageMapper;
    @Mock
    private CompensationRecordMapper compensationRecordMapper;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SeckillMetrics seckillMetrics;
    @Mock
    private Channel channel;

    private SeckillConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SeckillConsumer(
                orderService,
                seckillLogMapper,
                seckillMessageMapper,
                compensationRecordMapper,
                stringRedisTemplate,
                new SeckillProperties(),
                seckillMetrics
        );
    }

    @Test
    void consumeSkipsMessageWhenStatusIsNoLongerConsumable() throws Exception {
        SeckillMessage message = message();
        when(seckillMessageMapper.markConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()
        )).thenReturn(0);

        consumer.consume(message, rawMessage(), channel);

        verify(orderService, never()).createOrderFromConsumingMessage(message);
        verify(seckillMetrics).mq("consume_skipped");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void consumeMarksConsumingBeforeCreatingOrder() throws Exception {
        SeckillMessage message = message();
        when(seckillMessageMapper.markConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()
        )).thenReturn(1);

        consumer.consume(message, rawMessage(), channel);

        verify(orderService).createOrderFromConsumingMessage(message);
        verify(seckillMetrics).mq("consume_success");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void duplicateConsumeMarksConsumedFromConsumingBeforeAcking() throws Exception {
        SeckillMessage message = message();
        when(seckillMessageMapper.markConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()
        )).thenReturn(1);
        doThrow(new DuplicateKeyException("duplicate"))
                .when(orderService).createOrderFromConsumingMessage(message);
        when(seckillMessageMapper.markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        )).thenReturn(1);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        consumer.consume(message, rawMessage(), channel);

        verify(seckillMessageMapper).markConsumedFromConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
        verify(seckillMessageMapper, never()).updateStatus(REQUEST_ID, MessageStatus.CONSUMED.getCode());
        verify(seckillMetrics).mq("duplicate_acked");
        verify(channel).basicAck(DELIVERY_TAG, false);
    }

    @Test
    void transientDataAccessFailureReturnsMessageToRetryStateAndAcks() throws Exception {
        SeckillMessage message = message();
        when(seckillMessageMapper.markConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()
        )).thenReturn(1);
        doThrow(new TransientDataAccessResourceException("temporary database failure"))
                .when(orderService).createOrderFromConsumingMessage(message);
        when(seckillMessageMapper.markFailedFromConsuming(
                eq(REQUEST_ID),
                eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONSUMING.getCode()),
                eq("temporary database failure"),
                any()
        )).thenReturn(1);

        consumer.consume(message, rawMessage(), channel);

        verify(seckillMessageMapper).markFailedFromConsuming(
                eq(REQUEST_ID),
                eq(MessageStatus.FAILED.getCode()),
                eq(MessageStatus.CONSUMING.getCode()),
                eq("temporary database failure"),
                any()
        );
        verify(seckillMessageMapper, never()).markDead(any(), anyInt(), anyInt(), any());
        verify(compensationRecordMapper, never()).insert(any());
        verify(seckillMetrics).mq("transient_requeued");
        verify(channel).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void insufficientStockPersistsFailedOrderBeforeAcking() throws Exception {
        SeckillMessage message = message();
        when(seckillMessageMapper.markConsuming(
                REQUEST_ID,
                MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()
        )).thenReturn(1);
        doThrow(new InsufficientStockException("stock exhausted"))
                .when(orderService).createOrderFromConsumingMessage(message);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        consumer.consume(message, rawMessage(), channel);

        verify(seckillMessageMapper).markFailed(
                REQUEST_ID,
                MessageStatus.FAILED.getCode(),
                "stock exhausted"
        );
        verify(orderService).recordFailedOrder(message);
        verify(seckillMetrics).mq("stock_guard_failed");
        verify(channel).basicAck(DELIVERY_TAG, false);
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
