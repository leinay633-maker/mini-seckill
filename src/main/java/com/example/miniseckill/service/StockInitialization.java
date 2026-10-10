package com.example.miniseckill.service;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.mapper.SkuStockMapper;
import com.example.miniseckill.mapper.SkuStockSegmentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative bootstrap is INSERT-only. A Redis lease never authorizes resetting live MySQL facts. */
@Service
public class StockInitialization {
    private final SkuStockMapper stocks;
    private final SkuStockSegmentMapper segments;
    private final SeckillProperties properties;
    public StockInitialization(SkuStockMapper stocks, SkuStockSegmentMapper segments, SeckillProperties properties) {
        this.stocks=stocks; this.segments=segments; this.properties=properties;
    }
    @Transactional(rollbackFor = Exception.class)
    public void create(Long a, Long s, int stock) {
        try { if (stocks.insertStock(a,s,stock) != 1) throw new IllegalStateException("stock INSERT returned zero"); }
        catch (DuplicateKeyException e) { throw new BusinessException(409,"库存已存在；禁止重置活跃或历史活动，请使用新 SKU"); }
        if (properties.getMysqlStockSegment().isEnabled()) {
            int count=Math.max(1,properties.getMysqlStockSegment().getSegmentCount());
            for (int i=0;i<count;i++) segments.upsertSegment(a,s,i,stock/count+(i<stock%count?1:0));
        }
    }
}
