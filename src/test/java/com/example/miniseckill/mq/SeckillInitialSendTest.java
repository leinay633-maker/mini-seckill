package com.example.miniseckill.mq;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.config.RabbitMQConfig;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class SeckillInitialSendTest {
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final SeckillMessageMapper messages = mock(SeckillMessageMapper.class);
    private final AdmissionCapacityProperties properties = new AdmissionCapacityProperties();
    private final SeckillMessage message = new SeckillMessage("initial", 1L, 10L, 1001L, 1L);

    @Test
    void initialSendingSkipsOnlyTheRedundantUpdateAndKeepsRoutingAndCorrelation() {
        SeckillProducer producer = new SeckillProducer(rabbit, messages, properties);
        assertEquals(MessageStatus.SENDING.getCode(), producer.initialMessageStatus());
        producer.sendInitial(message);
        verifyNoInteractions(messages);
        ArgumentCaptor<MessagePostProcessor> post = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbit).convertAndSend(eq(RabbitMQConfig.SECKILL_ORDER_EXCHANGE), eq(RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY),
                same(message), post.capture(), argThat((CorrelationData data) -> "initial|initial".equals(data.getId())));
        Message raw = post.getValue().postProcessMessage(new Message(new byte[0], new MessageProperties()));
        assertEquals("initial|initial", raw.getMessageProperties().getCorrelationId());
    }

    @Test
    void disabledOptimizationRetainsPendingThenSendingProtocol() {
        properties.setInitialSendingEnabled(false);
        SeckillProducer producer = new SeckillProducer(rabbit, messages, properties);
        assertEquals(MessageStatus.PENDING.getCode(), producer.initialMessageStatus());
        producer.sendInitial(message);
        verifySendingUpdate();
    }

    @Test
    void retryPathStillPerformsTheStateGuardedUpdate() {
        new SeckillProducer(rabbit, messages, properties).send(message);
        verifySendingUpdate();
    }

    @Test
    void initialStateAndSendModeCannotDriftAfterConstruction() {
        properties.setInitialSendingEnabled(false);
        SeckillProducer producer = new SeckillProducer(rabbit, messages, properties);
        properties.setInitialSendingEnabled(true);
        assertEquals(MessageStatus.PENDING.getCode(), producer.initialMessageStatus());
        producer.sendInitial(message);
        verifySendingUpdate();
    }

    private void verifySendingUpdate() {
        verify(messages).claimSend(eq("initial"), anyString(), eq(5));
    }
}
