package com.example.miniseckill.job;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.service.InventoryCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReservationRecoveryJob {
    private static final Logger log=LoggerFactory.getLogger(ReservationRecoveryJob.class);
    private final SkuStockMapper stocks;
    private final InventoryCoordinator coordinator;
    private final SeckillProperties properties;
    private long cursor;
    public ReservationRecoveryJob(SkuStockMapper stocks, InventoryCoordinator coordinator, SeckillProperties properties) {
        this.stocks=stocks; this.coordinator=coordinator; this.properties=properties;
    }
    @Scheduled(fixedDelayString="${seckill.coordination.recovery-delay:1000}")
    public void recover() {
        // Each instance may scan. DB unique-key cancellation and idempotent settlement choose the winner.
        var rows=stocks.selectPageAfterId(cursor,Math.max(1,properties.getReconcile().getPageSize()));
        if (rows.isEmpty()) { cursor=0; return; }
        for (SkuStock row:rows) {
            cursor=row.getId();
            try { coordinator.recoverExpired(row.getActivityId(),row.getSkuId()); }
            catch (RuntimeException e) { log.warn("COORD_SCAN_FAILED activityId={} skuId={}",row.getActivityId(),row.getSkuId(),e); }
        }
    }
}
