package com.example.miniseckill.job;

import com.example.miniseckill.common.OrderStatus;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.CompensationRecord;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.CompensationRecordMapper;
import com.example.miniseckill.mapper.SeckillLogMapper;
import com.example.miniseckill.mapper.SeckillMessageMapper;
import com.example.miniseckill.service.InventoryCoordinator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes long-running queued orders so users do not stay in "queuing" forever.
 */
@Component
public class OrderTimeoutJob {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutJob.class);

    private final SeckillMessageMapper seckillMessageMapper;
    private final SeckillLogMapper seckillLogMapper;
    private final CompensationRecordMapper compensationRecordMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;
    private final InventoryCoordinator inventory;

    public OrderTimeoutJob(SeckillMessageMapper seckillMessageMapper,
                           SeckillLogMapper seckillLogMapper,
                           CompensationRecordMapper compensationRecordMapper,
                           StringRedisTemplate stringRedisTemplate,
                           SeckillProperties seckillProperties, InventoryCoordinator inventory) {
        this.inventory=inventory;
        this.seckillMessageMapper = seckillMessageMapper;
        this.seckillLogMapper = seckillLogMapper;
        this.compensationRecordMapper = compensationRecordMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
    }

    @Scheduled(fixedDelayString = "${seckill.order-timeout.fixed-delay:60000}")
    public void closeTimeoutOrders() {
        SeckillProperties.OrderTimeout timeout = seckillProperties.getOrderTimeout();
        if (!timeout.isEnabled()) {
            return;
        }
        List<SeckillMessageRecord> records = seckillMessageMapper.selectTimeoutDue(
                timeout.getQueuedTimeout().toNanos() / 1000L, timeout.getBatchSize());
        for (SeckillMessageRecord record : records) {
            try { markTimeout(record); }
            catch (RuntimeException ex) { log.warn("COORD_TIMEOUT_DEFERRED requestId={}", record.getRequestId(), ex); }
        }
    }

    private void markTimeout(SeckillMessageRecord record) {
        int updated = seckillMessageMapper.timeoutIfStale(record.getRequestId(),
                seckillProperties.getOrderTimeout().getQueuedTimeout().toNanos()/1000L);
        if (updated != 1) {
            log.info("skip timeout side effects because message status changed, requestId={}", record.getRequestId());
            return;
        }
        inventory.projectOwned(record.getActivityId(),record.getSkuId(),record.getUserId(),record.getRequestId(),
                OrderStatus.TIMEOUT,true);
        try {
            seckillLogMapper.insertLog(
                    record.getRequestId(),
                    record.getActivityId(),
                    record.getUserId(),
                    record.getSkuId(),
                    "ORDER_TIMEOUT"
            );
        } catch (Exception ex) {
            log.warn("insert timeout log failed, requestId={}", record.getRequestId(), ex);
        }
        try {
            CompensationRecord compensation = new CompensationRecord();
            compensation.setRequestId(record.getRequestId());
            compensation.setActivityId(record.getActivityId());
            compensation.setSkuId(record.getSkuId());
            compensation.setType("ORDER_TIMEOUT");
            compensation.setStatus("CLOSED");
            compensation.setDetail("queued order timeout");
            compensationRecordMapper.insert(compensation);
        } catch (Exception ex) {
            log.warn("insert timeout compensation failed, requestId={}", record.getRequestId(), ex);
        }
    }
}
