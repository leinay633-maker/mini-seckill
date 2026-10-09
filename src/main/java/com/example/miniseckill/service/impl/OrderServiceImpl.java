package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.InsufficientStockException;
import com.example.miniseckill.common.MessageStatus;
import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.OrderQueryResponse;
import com.example.miniseckill.dto.SeckillMessage;
import com.example.miniseckill.entity.SeckillOrder;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.mapper.SeckillOrderMapper;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import com.example.miniseckill.service.OrderIdGenerator;
import com.example.miniseckill.service.OrderService;
import com.example.miniseckill.service.SeckillMetrics;
import com.example.miniseckill.util.RedisKeyUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Persists orders and MySQL stock changes in one transaction.
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderServiceImpl.class);

    private final SeckillOrderMapper seckillOrderMapper;
    private final SkuStockMapper skuStockMapper;
    private final SeckillLogMapper seckillLogMapper;
    private final SeckillMessageMapper seckillMessageMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final SkuStockSegmentMapper skuStockSegmentMapper;
    private final SeckillMetrics seckillMetrics;
    private final OrderIdGenerator orderIdGenerator;

    public OrderServiceImpl(SeckillOrderMapper seckillOrderMapper,
                            SkuStockMapper skuStockMapper,
                            SeckillLogMapper seckillLogMapper,
                            SeckillMessageMapper seckillMessageMapper,
                            SkuStockSegmentMapper skuStockSegmentMapper,
                            StringRedisTemplate stringRedisTemplate,
                            SeckillProperties seckillProperties,
                            SeckillMetrics seckillMetrics,
                            OrderIdGenerator orderIdGenerator) {
        this.seckillOrderMapper = seckillOrderMapper;
        this.skuStockMapper = skuStockMapper;
        this.seckillLogMapper = seckillLogMapper;
        this.seckillMessageMapper = seckillMessageMapper;
        this.skuStockSegmentMapper = skuStockSegmentMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
        this.seckillMetrics = seckillMetrics;
        this.orderIdGenerator = orderIdGenerator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrderFromMessage(SeckillMessage message) {
        createOrder(message, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrderFromConsumingMessage(SeckillMessage message) {
        createOrder(message, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reconcileExistingOrderFromConsumingMessage(SeckillMessage message) {
        // The business order is immutable in this project. A duplicate order_id
        // (e.g. a worker-id collision) without this business key is NOT success.
        SeckillOrder existing = seckillOrderMapper.selectByUserSku(
                message.getActivityId(), message.getUserId(), message.getSkuId());
        if (existing == null) {
            throw new IllegalStateException("duplicate key without a matching business order");
        }
        completeFromOrderFact(message, existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordFailedOrder(SeckillMessage message) {
        // Called through the service proxy after the original consume transaction
        // rolled back. The FAILED order and terminal message commit together.
        SeckillOrder order = new SeckillOrder();
        order.setOrderId(orderIdGenerator.nextId());
        order.setActivityId(message.getActivityId());
        order.setUserId(message.getUserId());
        order.setSkuId(message.getSkuId());
        order.setStatus(OrderStatus.FAILED.getCode());
        try {
            seckillOrderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            // This call deliberately joins the current transaction. Do not replace
            // the actual SUCCESS/FAILED fact with an assumed failure.
            reconcileExistingOrderFromConsumingMessage(message);
            return;
        }
        completeFromOrderFact(message, order);
    }

    private void completeFromOrderFact(SeckillMessage message, SeckillOrder order) {
        OrderStatus status;
        if (Integer.valueOf(OrderStatus.SUCCESS.getCode()).equals(order.getStatus())) {
            status = OrderStatus.SUCCESS;
            markMessageConsumed(message, true);
        } else if (Integer.valueOf(OrderStatus.FAILED.getCode()).equals(order.getStatus())) {
            status = OrderStatus.FAILED;
            int updated = seckillMessageMapper.markDeadFromConsuming(
                    message.getRequestId(), MessageStatus.DEAD.getCode(),
                    MessageStatus.CONSUMING.getCode(), "terminal business failure: persisted FAILED order");
            if (updated != 1) {
                throw new IllegalStateException("message is no longer CONSUMING while recording failure");
            }
        } else {
            throw new IllegalStateException("existing order has no supported terminal status");
        }
        publishStatusAfterCommit(message, status, false);
    }

    private void createOrder(SeckillMessage message, boolean requireConsumingStatus) {
        SeckillOrder order = new SeckillOrder();
        order.setOrderId(orderIdGenerator.nextId());
        order.setActivityId(message.getActivityId());
        order.setUserId(message.getUserId());
        order.setSkuId(message.getSkuId());
        order.setStatus(OrderStatus.SUCCESS.getCode());

        seckillOrderMapper.insert(order);

        int updatedRows = decreaseMysqlStock(message);
        if (updatedRows != 1) {
            seckillMetrics.order("mysql_stock_guard_failed");
            throw new InsufficientStockException("MySQL stock guard rejected the order");
        }

        seckillLogMapper.insertLog(
                message.getRequestId(),
                message.getActivityId(),
                message.getUserId(),
                message.getSkuId(),
                "ORDER_SUCCESS"
        );
        markMessageConsumed(message, requireConsumingStatus);
        publishStatusAfterCommit(message, OrderStatus.SUCCESS, true);
    }

    /**
     * Redis is a disposable projection, not a participant in the MySQL transaction.
     * afterCommit exceptions would otherwise escape through the service proxy even
     * though MySQL has committed. Keep both projection and metric failures contained.
     * No SQL is executed from this callback.
     */
    private void publishStatusAfterCommit(SeckillMessage message, OrderStatus status, boolean newSuccess) {
        Runnable publish = () -> {
            try {
                setOrderStatus(message.getActivityId(), message.getUserId(), message.getSkuId(), status);
            } catch (Exception ex) {
                log.warn("committed order status cache write failed, requestId={}, status={}",
                        message.getRequestId(), status, ex);
                safeOrderMetric("status_cache_write_failed");
            }
            if (newSuccess) {
                safeOrderMetric("success");
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            // Supports direct unit-test construction. Production callers must use
            // the transactional Spring service; MySQL ITs exercise that proxy.
            publish.run();
        }
    }

    private void safeOrderMetric(String result) {
        try {
            seckillMetrics.order(result);
        } catch (Exception ex) {
            log.warn("order metric failed, result={}", result, ex);
        }
    }

    @Override
    public OrderQueryResponse queryOrder(Long activityId, Long userId, Long skuId) {
        SeckillOrder order = seckillOrderMapper.selectByUserSku(activityId, userId, skuId);
        if (order != null) {
            OrderStatus status = OrderStatus.fromCode(order.getStatus());
            return new OrderQueryResponse(
                    activityId,
                    userId,
                    skuId,
                    order.getOrderId(),
                    status.getCode(),
                    status.getText()
            );
        }

        String statusValue = stringRedisTemplate.opsForValue().get(RedisKeyUtil.orderStatusKey(activityId, userId, skuId));
        Integer statusCode = parseIntOrNull(statusValue);
        if (statusCode != null) {
            OrderStatus orderStatus = OrderStatus.fromCode(statusCode);
            return new OrderQueryResponse(activityId, userId, skuId, null, orderStatus.getCode(), orderStatus.getText());
        }

        // Status comes from facts only: the order row, then the Redis status key (which carries the
        // exact FAILED/QUEUING/SUCCESS/TIMEOUT code written by the state machine), then the idempotency
        // key. The old log-text contains("FAILED") heuristic was brittle — a reworded log line silently
        // broke it — and is intentionally gone.
        Boolean hasQueueKey = stringRedisTemplate.hasKey(RedisKeyUtil.userSkuKey(activityId, userId, skuId));
        if (Boolean.TRUE.equals(hasQueueKey)) {
            return new OrderQueryResponse(activityId, userId, skuId, null, OrderStatus.QUEUING.getCode(), OrderStatus.QUEUING.getText());
        }

        return new OrderQueryResponse(activityId, userId, skuId, null, OrderStatus.NOT_ORDERED.getCode(), OrderStatus.NOT_ORDERED.getText());
    }

    private Integer parseIntOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            // Defensive: the status key is written by this app as a numeric code, but a polluted or
            // legacy value must degrade to "no status" rather than 500 the query.
            return null;
        }
    }

    private void setOrderStatus(Long activityId, Long userId, Long skuId, OrderStatus status) {
        stringRedisTemplate.opsForValue().set(
                RedisKeyUtil.orderStatusKey(activityId, userId, skuId),
                String.valueOf(status.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
    }

    private void markMessageConsumed(SeckillMessage message, boolean requireConsumingStatus) {
        int updated;
        if (!requireConsumingStatus) {
            updated = seckillMessageMapper.updateStatus(message.getRequestId(), MessageStatus.CONSUMED.getCode());
        } else {
            updated = seckillMessageMapper.markConsumedFromConsuming(
                    message.getRequestId(),
                    MessageStatus.CONSUMED.getCode(),
                    MessageStatus.CONSUMING.getCode()
            );
        }
        if (updated != 1) {
            throw new IllegalStateException("message status no longer permits order completion");
        }
    }

    private int decreaseMysqlStock(SeckillMessage message) {
        if (!seckillProperties.getMysqlStockSegment().isEnabled()) {
            return skuStockMapper.decreaseStock(message.getActivityId(), message.getSkuId());
        }
        if (skuStockSegmentMapper.countSegments(message.getActivityId(), message.getSkuId()) == 0) {
            return skuStockMapper.decreaseStock(message.getActivityId(), message.getSkuId());
        }
        int segmentCount = Math.max(1, seckillProperties.getMysqlStockSegment().getSegmentCount());
        int preferredSegment = Math.floorMod(message.getUserId().hashCode(), segmentCount);
        int updatedRows = skuStockSegmentMapper.decreaseSegmentStock(
                message.getActivityId(),
                message.getSkuId(),
                preferredSegment
        );
        if (updatedRows == 1) {
            return 1;
        }
        return skuStockSegmentMapper.decreaseAnySegmentStock(message.getActivityId(), message.getSkuId());
    }
}
