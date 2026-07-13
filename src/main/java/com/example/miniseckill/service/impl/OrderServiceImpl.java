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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists orders and MySQL stock changes in one transaction.
 */
@Service
public class OrderServiceImpl implements OrderService {

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
        setOrderStatus(message.getActivityId(), message.getUserId(), message.getSkuId(), OrderStatus.SUCCESS);
        seckillMetrics.order("success");
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
        if (statusValue != null) {
            int status = Integer.parseInt(statusValue);
            OrderStatus orderStatus = OrderStatus.fromCode(status);
            return new OrderQueryResponse(activityId, userId, skuId, null, orderStatus.getCode(), orderStatus.getText());
        }

        String latestResult = seckillLogMapper.selectLatestResult(activityId, userId, skuId);
        if (latestResult != null
                && (latestResult.contains("FAILED")
                || latestResult.contains("NOT_ENOUGH")
                || latestResult.contains("NOT_FOUND"))) {
            return new OrderQueryResponse(activityId, userId, skuId, null, OrderStatus.FAILED.getCode(), OrderStatus.FAILED.getText());
        }

        Boolean hasQueueKey = stringRedisTemplate.hasKey(RedisKeyUtil.userSkuKey(activityId, userId, skuId));
        if (Boolean.TRUE.equals(hasQueueKey)) {
            return new OrderQueryResponse(activityId, userId, skuId, null, OrderStatus.QUEUING.getCode(), OrderStatus.QUEUING.getText());
        }

        return new OrderQueryResponse(activityId, userId, skuId, null, OrderStatus.NOT_ORDERED.getCode(), OrderStatus.NOT_ORDERED.getText());
    }

    private void setOrderStatus(Long activityId, Long userId, Long skuId, OrderStatus status) {
        stringRedisTemplate.opsForValue().set(
                RedisKeyUtil.orderStatusKey(activityId, userId, skuId),
                String.valueOf(status.getCode()),
                seckillProperties.getOrderStatusTtl()
        );
    }

    private void markMessageConsumed(SeckillMessage message, boolean requireConsumingStatus) {
        if (!requireConsumingStatus) {
            seckillMessageMapper.updateStatus(message.getRequestId(), MessageStatus.CONSUMED.getCode());
            return;
        }
        int updated = seckillMessageMapper.markConsumedFromConsuming(
                message.getRequestId(),
                MessageStatus.CONSUMED.getCode(),
                MessageStatus.CONSUMING.getCode()
        );
        if (updated != 1) {
            throw new IllegalStateException("message is not in CONSUMING status");
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
