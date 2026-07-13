package com.example.miniseckill.mq;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@ExtendWith(MockitoExtension.class)
class SeckillProducerTest {

    private static final String REQUEST_ID = "req-001";

    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private SeckillMessageMapper seckillMessageMapper;

    @Test
    void confirmAckMarksSentOnlyFromSending() {
        SeckillProducer producer = new SeckillProducer(rabbitTemplate, seckillMessageMapper);

        producer.confirm(new CorrelationData(REQUEST_ID), true, null);

        verify(seckillMessageMapper).markSentFromSending(
                REQUEST_ID,
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode()
        );
    }

    @Test
    void confirmNackMarksPublishFailedOnlyFromSending() {
        SeckillProducer producer = new SeckillProducer(rabbitTemplate, seckillMessageMapper);

        producer.confirm(new CorrelationData(REQUEST_ID), false, "nack");

        verify(seckillMessageMapper).markPublishFailedFromSending(
                REQUEST_ID,
                MessageStatus.CONFIRM_FAILED.getCode(),
                MessageStatus.SENDING.getCode(),
                "nack"
        );
    }

    @Test
    void returnedMessageMarksReturnedOnlyFromSending() {
        SeckillProducer producer = new SeckillProducer(rabbitTemplate, seckillMessageMapper);
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId(REQUEST_ID);
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0], properties),
                312,
                "NO_ROUTE",
                "exchange",
                "routing"
        );

        producer.returnedMessage(returned);

        verify(seckillMessageMapper).markPublishFailedFromSending(
                REQUEST_ID,
                MessageStatus.RETURNED.getCode(),
                MessageStatus.SENDING.getCode(),
                "312:NO_ROUTE"
        );
    }

    @Test
    void returnedMessageWithoutCorrelationIdDoesNotUpdateStatus() {
        SeckillProducer producer = new SeckillProducer(rabbitTemplate, seckillMessageMapper);
        ReturnedMessage returned = new ReturnedMessage(
                new Message(new byte[0], new MessageProperties()),
                312,
                "NO_ROUTE",
                "exchange",
                "routing"
        );

        producer.returnedMessage(returned);

        verifyNoInteractions(seckillMessageMapper);
    }
}
