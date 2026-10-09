package com.example.miniseckill.mq;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.config.RabbitMQConfig;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Sends accepted seckill requests to RabbitMQ and updates local message status by confirm callbacks.
 */
@Component
public class SeckillProducer implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    private static final Logger log = LoggerFactory.getLogger(SeckillProducer.class);

    private final RabbitTemplate rabbitTemplate;
    private final SeckillMessageMapper seckillMessageMapper;
    private final int initialMessageStatus;

    @Autowired
    public SeckillProducer(RabbitTemplate rabbitTemplate, SeckillMessageMapper seckillMessageMapper,
                            AdmissionCapacityProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.seckillMessageMapper = seckillMessageMapper;
        // Freeze both halves of the protocol together; runtime property mutation must not
        // make an INSERT use PENDING while its initial send assumes SENDING.
        this.initialMessageStatus = properties.isInitialSendingEnabled()
                ? MessageStatus.SENDING.getCode() : MessageStatus.PENDING.getCode();
    }

    public SeckillProducer(RabbitTemplate rabbitTemplate, SeckillMessageMapper seckillMessageMapper) {
        this(rabbitTemplate, seckillMessageMapper, new AdmissionCapacityProperties());
    }

    @PostConstruct
    public void initCallbacks() {
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
    }

    /** State for the durable initial INSERT, which must finish before sendInitial is called. */
    public int initialMessageStatus() {
        return initialMessageStatus;
    }

    /**
     * Only the just-persisted admission path uses this method. SENDING is a durable send intent,
     * not proof of publication. A crash here leaves a row eligible for the existing retry scan.
     * Retry/replay callers MUST continue using send(), including its state-guarded UPDATE.
     */
    public void sendInitial(SeckillMessage message) {
        if (initialMessageStatus == MessageStatus.SENDING.getCode()) {
            publish(message);
        } else {
            send(message);
        }
    }

    public void send(SeckillMessage message) {
        seckillMessageMapper.markSending(
                message.getRequestId(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.TIMEOUT.getCode(),
                MessageStatus.DEAD.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
        publish(message);
    }

    private void publish(SeckillMessage message) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.SECKILL_ORDER_EXCHANGE,
                RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY,
                message,
                rawMessage -> {
                    rawMessage.getMessageProperties().setCorrelationId(message.getRequestId());
                    return rawMessage;
                },
                new CorrelationData(message.getRequestId())
        );
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (correlationData == null || correlationData.getId() == null) {
            return;
        }
        String requestId = correlationData.getId();
        if (ack) {
            seckillMessageMapper.markSentFromSending(
                    requestId,
                    MessageStatus.SENT.getCode(),
                    MessageStatus.SENDING.getCode()
            );
            log.debug("rabbitmq confirm ack, requestId={}", requestId);
        } else {
            seckillMessageMapper.markPublishFailedFromSending(
                    requestId,
                    MessageStatus.CONFIRM_FAILED.getCode(),
                    MessageStatus.SENDING.getCode(),
                    cause == null ? "publisher confirm nack" : cause
            );
            log.warn("rabbitmq confirm nack, requestId={}, cause={}", requestId, cause);
        }
    }

    @Override
    public void returnedMessage(ReturnedMessage returned) {
        String requestId = returned.getMessage().getMessageProperties().getCorrelationId();
        if (requestId == null) {
            log.warn("rabbitmq returned message without correlation id, replyCode={}, replyText={}",
                    returned.getReplyCode(), returned.getReplyText());
            return;
        }
        seckillMessageMapper.markPublishFailedFromSending(
                requestId,
                MessageStatus.RETURNED.getCode(),
                MessageStatus.SENDING.getCode(),
                returned.getReplyCode() + ":" + returned.getReplyText()
        );
        log.warn("rabbitmq returned message, requestId={}, replyCode={}, replyText={}",
                requestId, returned.getReplyCode(), returned.getReplyText());
    }
}
