package com.example.miniseckill.job;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SkuStock;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.service.InventoryCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Discovery pages do not authorize writes; every SKU is reread through the guarded protocol. */
@Component
public class StockReconcileJob {
    private static final Logger log=LoggerFactory.getLogger(StockReconcileJob.class);
    private final SkuStockMapper stocks;
    private final InventoryCoordinator inventory;
    private final SeckillProperties properties;
    private long cursor;
    public StockReconcileJob(SkuStockMapper stocks, InventoryCoordinator inventory, SeckillProperties properties) {
        this.stocks=stocks; this.inventory=inventory; this.properties=properties;
    }
    @Scheduled(fixedDelayString="${seckill.reconcile.fixed-delay:60000}")
    public void reconcileStock() {
        var config=properties.getReconcile();
        if (!config.isEnabled()) return;
        int remaining=Math.max(1,config.getScanLimit());
        while (remaining>0) {
            int limit=Math.min(Math.max(1,config.getPageSize()),remaining);
            var rows=stocks.selectPageAfterId(cursor,limit);
            if (rows.isEmpty()) { cursor=0; return; }
            for (SkuStock row:rows) {
                cursor=row.getId();
                try { inventory.reconcile(row.getActivityId(),row.getSkuId()); }
                catch (RuntimeException e) { log.warn("COORD_RECONCILE_FAILED activityId={} skuId={}",row.getActivityId(),row.getSkuId(),e); }
            }
            remaining-=rows.size();
            if (rows.size()<limit) { cursor=0; return; }
        }
    }
}
