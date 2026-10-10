package com.example.miniseckill.mq;

import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.config.RabbitMQConfig;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.service.CoordinationFaults;
import jakarta.annotation.PostConstruct;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** A database-owned send attempt. Duplicate publication remains possible; callbacks are generation-fenced. */
@Component
public class SeckillProducer implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {
    private static final Logger log=LoggerFactory.getLogger(SeckillProducer.class);
    private final RabbitTemplate rabbit;
    private final SeckillMessageMapper messages;
    private final int initialStatus;
    private final int maxRetry;
    private final CoordinationFaults faults;
    @Autowired
    public SeckillProducer(RabbitTemplate rabbit, SeckillMessageMapper messages, AdmissionCapacityProperties capacity,
                           SeckillProperties properties, CoordinationFaults faults) {
        this.rabbit=rabbit; this.messages=messages;
        this.initialStatus=capacity.isInitialSendingEnabled()?9:0;
        this.maxRetry=properties.getMessageRetry().getMaxRetry(); this.faults=faults;
    }
    public SeckillProducer(RabbitTemplate rabbit, SeckillMessageMapper messages, AdmissionCapacityProperties capacity) {
        this(rabbit,messages,capacity,new SeckillProperties(),null);
    }
    public SeckillProducer(RabbitTemplate rabbit, SeckillMessageMapper messages) {
        this(rabbit,messages,new AdmissionCapacityProperties());
    }
    @PostConstruct
    public void initCallbacks() { rabbit.setConfirmCallback(this); rabbit.setReturnsCallback(this); }
    public int initialMessageStatus() { return initialStatus; }
    public void sendInitial(SeckillMessage message) {
        if (initialStatus==9) publish(message,message.getRequestId()); // token persisted by the initial INSERT
        else send(message);
    }
    /** False means another attempt/terminal state owns the row; no publish and no retry accounting. */
    public boolean send(SeckillMessage message) {
        String token=UUID.randomUUID().toString();
        if (messages.claimSend(message.getRequestId(),token,maxRetry)!=1) return false;
        if (faults!=null) faults.hit("after-send-claim",message.getRequestId()+" "+token);
        publish(message,token); return true;
    }
    private void publish(SeckillMessage message, String token) {
        String correlation=message.getRequestId()+"|"+token;
        try {
            rabbit.convertAndSend(RabbitMQConfig.SECKILL_ORDER_EXCHANGE,RabbitMQConfig.SECKILL_ORDER_ROUTING_KEY,message,
                raw -> { raw.getMessageProperties().setCorrelationId(correlation); return raw; },new CorrelationData(correlation));
        } catch (RuntimeException error) {
            try { finish(message.getRequestId(),token,MessageStatus.FAILED.getCode(),shortError(error.getMessage())); }
            catch (RuntimeException updateFailed) { error.addSuppressed(updateFailed); }
            throw error;
        }
    }
    @Override
    public void confirm(CorrelationData data, boolean ack, String cause) {
        if (data==null) return;
        String[] attempt=parse(data.getId());
        if (attempt==null) return;
        // Returned messages must not be marked SENT by a subsequent positive publisher confirm.
        int status=ack && data.getReturned()==null ? MessageStatus.SENT.getCode() : MessageStatus.CONFIRM_FAILED.getCode();
        finish(attempt[0],attempt[1],status,ack?null:shortError(cause));
    }
    @Override
    public void returnedMessage(ReturnedMessage returned) {
        String[] attempt=parse(returned.getMessage().getMessageProperties().getCorrelationId());
        if (attempt==null) return;
        finish(attempt[0],attempt[1],MessageStatus.RETURNED.getCode(),shortError(returned.getReplyCode()+":"+returned.getReplyText()));
    }
    private void finish(String id, String token, int status, String error) {
        int changed=messages.finishSend(id,token,status,error);
        log.info("COORD_SEND_RESULT requestId={} token={} status={} changed={}",id,token,status,changed);
    }
    private static String[] parse(String value) {
        if (value==null) return null;
        int delimiter=value.indexOf('|');
        if (delimiter<=0 || delimiter==value.length()-1 || value.indexOf('|',delimiter+1)>=0) return null;
        return new String[]{value.substring(0,delimiter),value.substring(delimiter+1)};
    }
    private static String shortError(String value) { return value==null?null:value.substring(0,Math.min(value.length(),512)); }
}
