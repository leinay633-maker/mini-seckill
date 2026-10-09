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
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/**
 * Consumes seckill messages with manual ACK and fact-based duplicate handling.
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
        if (!tryMarkConsuming(seckillMessage)) {
            safeLog(seckillMessage, "CONSUME_SKIPPED_FINAL_OR_TIMEOUT");
            safeMetric("consume_skipped");
            channel.basicAck(deliveryTag, false);
            return;
        }
        boolean deadLetter = false;
        String outcome = "consume_success";
        try {
            // The proxy returns only after the MySQL transaction has committed.
            orderService.createOrderFromConsumingMessage(seckillMessage);
        } catch (DuplicateKeyException ex) {
            // A duplicate order_id is not necessarily this user's order, and a
            // business-key duplicate can point to FAILED. Resolve durable facts.
            orderService.reconcileExistingOrderFromConsumingMessage(seckillMessage);
            safeLog(seckillMessage, "DUPLICATE_CONSUME_RECONCILED");
            outcome = "duplicate_acked";
        } catch (InsufficientStockException ex) {
            // This service call commits FAILED order + terminal message together.
            // If it fails, propagate without ACK; never swallow the persistence error.
            orderService.recordFailedOrder(seckillMessage);
            safeLog(seckillMessage, "DB_STOCK_NOT_ENOUGH_ACKED");
            insertCompensation(seckillMessage, "MYSQL_STOCK_GUARD", "FAILED", ex.getMessage());
            outcome = "stock_guard_failed";
        } catch (TransientDataAccessException ex) {
            int rolledBack = seckillMessageMapper.markFailedFromConsuming(
                    seckillMessage.getRequestId(),
                    MessageStatus.FAILED.getCode(),
                    MessageStatus.CONSUMING.getCode(),
                    shortError(ex),
                    java.time.LocalDateTime.now().plus(seckillProperties.getMessageRetry().getInitialBackoff())
            );
            if (rolledBack == 1) {
                safeLog(seckillMessage, "TRANSIENT_CONSUME_RETRY");
                outcome = "transient_requeued";
            } else {
                // Recovery or another completion won. A lost CAS is not authority
                // to mark DEAD or overwrite a newer order status.
                outcome = "consume_state_changed";
            }
        } catch (Exception ex) {
            log.error("consume seckill message failed, requestId={}", seckillMessage.getRequestId(), ex);
            int updated = seckillMessageMapper.markDeadFromConsuming(
                    seckillMessage.getRequestId(),
                    MessageStatus.DEAD.getCode(),
                    MessageStatus.CONSUMING.getCode(),
                    shortError(ex)
            );
            if (updated == 1) {
                insertCompensation(seckillMessage, "MQ_CONSUME_DEAD", "WAIT_REPLAY", shortError(ex));
                setOrderStatusSafely(seckillMessage, OrderStatus.FAILED);
                outcome = "dead_lettered";
                deadLetter = true;
            } else {
                outcome = "consume_state_changed";
            }
        }
        safeMetric(outcome);
        // Transport settlement is intentionally OUTSIDE the business exception
        // handler. An ACK failure cannot undo a committed order, write FAILED,
        // trigger compensation, or cause a second settlement on the same delivery.
        if (deadLetter) {
            channel.basicNack(deliveryTag, false, false);
        } else {
            channel.basicAck(deliveryTag, false);
        }
    }

    private boolean tryMarkConsuming(SeckillMessage message) {
        int updated = seckillMessageMapper.markConsuming(
                message.getRequestId(),
                MessageStatus.CONSUMING.getCode(),
                MessageStatus.SENT.getCode(),
                MessageStatus.SENDING.getCode(),
                MessageStatus.REPLAYED.getCode()
        );
        if (updated == 1) {
            return true;
        }
        log.info("skip consumed seckill message because status is no longer consumable, requestId={}", message.getRequestId());
        return false;
    }

    private void safeLog(SeckillMessage message, String result) {
        try {
            seckillLogMapper.insertLog(message.getRequestId(), message.getActivityId(), message.getUserId(), message.getSkuId(), result);
        } catch (Exception ex) {
            log.warn("insert consume log failed, requestId={}, result={}", message.getRequestId(), result, ex);
        }
    }

    private void safeMetric(String outcome) {
        try {
            seckillMetrics.mq(outcome);
        } catch (Exception ex) {
            log.warn("consume metric failed, outcome={}", outcome, ex);
        }
    }

    private void setOrderStatusSafely(SeckillMessage message, OrderStatus status) {
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyUtil.orderStatusKey(message.getActivityId(), message.getUserId(), message.getSkuId()),
                    String.valueOf(status.getCode()),
                    seckillProperties.getOrderStatusTtl()
            );
        } catch (Exception ex) {
            log.warn("terminal message status cache write failed, requestId={}", message.getRequestId(), ex);
        }
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
