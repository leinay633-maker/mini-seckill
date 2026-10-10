package com.example.miniseckill.mq;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.ConsumerExecutionProperties;
import com.example.miniseckill.config.ConsumerPoolContext;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.config.RabbitMQConfig;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.service.ConsumerOrderTransactions;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.util.RedisKeyUtil;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/** Manual settlement is separate from all database work, including error handlers. */
@Component
public class SeckillConsumer {
    private static final Logger log = LoggerFactory.getLogger(SeckillConsumer.class);
    private final ConsumerOrderTransactions transactions;
    private final SeckillLogMapper seckillLogMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final ConsumerExecutionProperties execution;
    private final SeckillMetrics seckillMetrics;

    public SeckillConsumer(ConsumerOrderTransactions transactions, SeckillLogMapper seckillLogMapper,
                           CompensationRecordMapper compensationRecordMapper,
                           StringRedisTemplate stringRedisTemplate, SeckillProperties seckillProperties,
                           ConsumerExecutionProperties execution, SeckillMetrics seckillMetrics) {
        this.transactions = transactions;
        this.seckillLogMapper = seckillLogMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
        this.execution = execution;
        this.seckillMetrics = seckillMetrics;
    }

    @RabbitListener(queues = RabbitMQConfig.SECKILL_ORDER_QUEUE, containerFactory = "manualAckRabbitListenerContainerFactory")
    public void consume(SeckillMessage message, Message rawMessage, Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        Outcome outcome;
        // Set routing BEFORE entering the transaction proxy; always clear it on
        // return/error so a pooled listener thread cannot leak its role.
        try (ConsumerPoolContext.Scope ignored = ConsumerPoolContext.enter()) {
            try {
                outcome = process(message);
            } catch (Exception unresolved) {
                // Includes claim errors and failures INSIDE reconcile/failStock/
                // markDead handlers. Do not attempt another SQL write while starved.
                log.warn("consume deferred; broker will retry, requestId={}", message.getRequestId(), unresolved);
                outcome = new Outcome("consume_retry_requeued", Settlement.REQUEUE);
            }
        }
        safeMetric(outcome.metric());
        if (outcome.settlement() == Settlement.REQUEUE) {
            // The transaction proxy has rolled back/released its connection.
            // Pace infrastructure retries without spending the durable retry budget.
            try {
                Thread.sleep(execution.getRetryBackoff().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        settle(channel, deliveryTag, outcome.settlement());
    }

    private Outcome process(SeckillMessage message) {
        try {
            return transactions.create(message)
                    ? new Outcome("consume_success", Settlement.ACK) : skipped();
        } catch (DuplicateKeyException ex) {
            boolean reconciled = transactions.reconcile(message);
            if (reconciled) { safeLog(message, "DUPLICATE_CONSUME_RECONCILED"); }
            return reconciled ? new Outcome("duplicate_acked", Settlement.ACK) : skipped();
        } catch (InsufficientStockException ex) {
            boolean recorded = transactions.failStock(message);
            if (recorded) {
                safeLog(message, "DB_STOCK_NOT_ENOUGH_ACKED");
                insertCompensation(message, "MYSQL_STOCK_GUARD", "FAILED", ex.getMessage());
            }
            return recorded ? new Outcome("stock_guard_failed", Settlement.ACK) : skipped();
        } catch (Exception ex) {
            if (ConsumerFailureClassifier.retryable(ex)) { throw ex; }
            // The original attempt rolled back, including its claim. A distinct
            // transaction must claim again before it may commit a DEAD outcome.
            boolean dead = transactions.markDead(message, shortError(ex));
            if (!dead) { return skipped(); }
            log.error("consume failed permanently, requestId={}", message.getRequestId(), ex);
            insertCompensation(message, "MQ_CONSUME_DEAD", "WAIT_REVIEW", shortError(ex));
            setOrderStatusSafely(message, OrderStatus.FAILED);
            return new Outcome("dead_lettered", Settlement.DEAD);
        }
    }

    private Outcome skipped() {
        return new Outcome("consume_skipped", Settlement.ACK);
    }

    private void settle(Channel channel, long tag, Settlement settlement) throws IOException {
        try {
            switch (settlement) {
                case ACK -> channel.basicAck(tag, false);
                case REQUEUE -> channel.basicNack(tag, false, true);
                case DEAD -> channel.basicNack(tag, false, false);
            }
        } catch (IOException | RuntimeException failure) {
            // An ACK/NACK failure must never enter the business handler or cause
            // a second settlement. Close the *physical* channel so outstanding
            // deliveries can be recovered; a cached proxy.close() is insufficient.
            try {
                Channel physical = channel instanceof ChannelProxy proxy ? proxy.getTargetChannel() : channel;
                physical.abort();
            } catch (Exception closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void safeLog(SeckillMessage message, String result) {
        try {
            seckillLogMapper.insertLog(message.getRequestId(), message.getActivityId(), message.getUserId(), message.getSkuId(), result);
        } catch (Exception ex) {
            log.warn("insert consume log failed, requestId={}, result={}", message.getRequestId(), result, ex);
        }
    }

    private void safeMetric(String outcome) {
        try { seckillMetrics.mq(outcome); }
        catch (Exception ex) { log.warn("consume metric failed, outcome={}", outcome, ex); }
    }

    private void setOrderStatusSafely(SeckillMessage message, OrderStatus status) {
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyUtil.orderStatusKey(message.getActivityId(), message.getUserId(), message.getSkuId()),
                    String.valueOf(status.getCode()), seckillProperties.getOrderStatusTtl());
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
            record.setDetail(detail == null ? "" : detail.substring(0, Math.min(1024, detail.length())));
            compensationRecordMapper.insert(record);
        } catch (Exception ex) {
            log.warn("insert compensation record failed, requestId={}, type={}", message.getRequestId(), type, ex);
        }
    }

    private String shortError(Exception ex) {
        String text = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return text.substring(0, Math.min(500, text.length()));
    }

    private enum Settlement { ACK, REQUEUE, DEAD }
    private record Outcome(String metric, Settlement settlement) { }
}
