package com.example.miniseckill.service.impl;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RedisRecoveryResponse;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.service.*;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Same sink-fenced protocol as periodic reconcile and warmup, not a process-local write barrier. */
@Service
public class RedisRecoveryServiceImpl implements RedisRecoveryService {
    private static final Logger log=LoggerFactory.getLogger(RedisRecoveryServiceImpl.class);
    private final SkuStockMapper stocks;
    private final InventoryCoordinator inventory;
    private final SeckillProperties properties;
    private final RedisRecoveryStateService state;
    public RedisRecoveryServiceImpl(SkuStockMapper stocks, InventoryCoordinator inventory,
            SeckillProperties properties, RedisRecoveryStateService state) {
        this.stocks=stocks; this.inventory=inventory; this.properties=properties; this.state=state;
    }
    @Override
    public RedisRecoveryResponse recoverRedisStock() {
        state.markRecovering("guarded Redis stock recovery is running");
        RedisRecoveryResponse response=new RedisRecoveryResponse();
        response.setStartedAt(LocalDateTime.now());
        long cursor=0;
        int remaining=Math.max(1,properties.getRedisRecovery().getScanLimit());
        boolean failed=false;
        try {
            while (remaining>0) {
                var rows=stocks.selectPageAfterId(cursor,Math.min(100,remaining));
                if (rows.isEmpty()) break;
                for (var row:rows) {
                    cursor=row.getId(); remaining--;
                    response.setScannedSkuCount(response.getScannedSkuCount()+1);
                    try {
                        var result=inventory.reconcile(row.getActivityId(),row.getSkuId());
                        if (!result.complete()) throw new BusinessException(503,result.outcome());
                        response.setRecoveredSkuCount(response.getRecoveredSkuCount()+1);
                        response.setTotalExpectedRedisStock(response.getTotalExpectedRedisStock()+result.expected());
                    } catch (RuntimeException e) {
                        failed=true; response.setSkippedSkuCount(response.getSkippedSkuCount()+1);
                        log.warn("COORD_RECOVERY_SKIPPED activityId={} skuId={}",row.getActivityId(),row.getSkuId(),e);
                    }
                }
            }
            // A scan budget is not proof that every SKU was recovered.
            if (!stocks.selectPageAfterId(cursor,1).isEmpty()) failed=true;
            if (failed) throw new BusinessException(503,"Redis 恢复未完成：存在忙碌、在途、元数据丢失或未扫描 SKU");
            state.markRecovered();
            response.setMessage("已扫描 SKU 的库存协调核对完成");
            return response;
        } finally { response.setFinishedAt(LocalDateTime.now()); }
    }
}
