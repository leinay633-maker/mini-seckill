package com.example.miniseckill.mq;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.config.RabbitMQConfig;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.util.RedisKeyUtil;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Consumes seckill messages with manual ACK and idempotent duplicate handling.
 */
@Component
public class SeckillConsumer {

    private static final Logger log = LoggerFactory.getLogger(SeckillConsumer.class);

    private final OrderService orderService;
    private final SeckillLogMapper seckillLogMapper;
    private final SeckillMessageMapper seckillMessageMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final SeckillMetrics seckillMetrics;

    public SeckillConsumer(OrderService orderService,
                           SeckillLogMapper seckillLogMapper,
                           SeckillMessageMapper seckillMessageMapper,
                           CompensationRecordMapper compensationRecordMapper,
                           StringRedisTemplate stringRedisTemplate,
                           SeckillProperties seckillProperties,
                           SeckillMetrics seckillMetrics) {
        this.orderService = orderService;
        this.seckillLogMapper = seckillLogMapper;
        this.seckillMessageMapper = seckillMessageMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
        this.seckillMetrics = seckillMetrics;
    }

    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE, containerFactory = "manualAckRabbitListenerContainerFactory")
    public void consume(SeckillMessage seckillMessage, Message rawMessage, Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        try {
            orderService.createOrderFromMessage(seckillMessage);
            seckillMetrics.mq("consume_success");
            channel.basicAck(deliveryTag, false);
        } catch (DuplicateKeyException ex) {
            safeLog(seckillMessage, "DUPLICATE_CONSUME_ACKED");
            seckillMessageMapper.updateStatus(seckillMessage.getRequestId(), MessageStatus.CONSUMED.getCode());
            setOrderStatus(seckillMessage, OrderStatus.SUCCESS);
            seckillMetrics.mq("duplicate_acked");
            channel.basicAck(deliveryTag, false);
        } catch (InsufficientStockException ex) {
            safeLog(seckillMessage, "DB_STOCK_NOT_ENOUGH_ACKED");
            seckillMessageMapper.markFailed(seckillMessage.getRequestId(), MessageStatus.FAILED.getCode(), ex.getMessage());
            setOrderStatus(seckillMessage, OrderStatus.FAILED);
            insertCompensation(seckillMessage, "MYSQL_STOCK_GUARD", "FAILED", ex.getMessage());
            seckillMetrics.mq("stock_guard_failed");
            channel.basicAck(deliveryTag, false);
        } catch (Exception ex) {
            log.error("consume seckill message failed, requestId={}", seckillMessage.getRequestId(), ex);
            seckillMessageMapper.markDead(
                    seckillMessage.getRequestId(),
                    MessageStatus.DEAD.getCode(),
                    MessageStatus.CONSUMED.getCode(),
                    shortError(ex)
            );
            insertCompensation(seckillMessage, "MQ_CONSUME_DEAD", "WAIT_REPLAY", shortError(ex));
            setOrderStatus(seckillMessage, OrderStatus.FAILED);
            seckillMetrics.mq("dead_lettered");
            channel.basicNack(deliveryTag, false, false);
        }
    }

    private void safeLog(SeckillMessage message, String result) {
        try {
            seckillLogMapper.insertLog(message.getRequestId(), message.getActivityId(), message.getUserId(), message.getSkuId(), result);
        } catch (Exception ex) {
            log.warn("insert consume log failed, requestId={}, result={}", message.getRequestId(), result, ex);
        }
    }

    private void setOrderStatus(SeckillMessage message, OrderStatus status) {
        stringRedisTemplate.opsForValue().set(
                RedisKeyUtil.orderStatusKey(message.getActivityId(), message.getUserId(), message.getSkuId()),
                String.valueOf(status.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
    }

    private void insertCompensation(SeckillMessage message, String type, String status, String detail) {
        try {
            CompensationRecord record = new CompensationRecord();
            record.setRequestId(message.getRequestId());
            record.setActivityId(message.getActivityId());
            record.setSkuId(message.getSkuId());
            record.setType(type);
            record.setStatus(status);
            record.setDetail(detail == null ? "" : shortText(detail, 1024));
            compensationRecordMapper.insert(record);
        } catch (Exception ex) {
            log.warn("insert compensation record failed, requestId={}, type={}", message.getRequestId(), type, ex);
        }
    }

    private String shortError(Exception ex) {
        String message = ex.getMessage();
        if (message == null) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private String shortText(String text, int maxLength) {
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
