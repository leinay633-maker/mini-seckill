package com.example.miniseckill.mapper;

import com.example.miniseckill.entity.StockFacts;
import org.apache.ibatis.annotations.*;

@Mapper
public interface StockFactsMapper {
    // No outer transaction: caller must not reuse an older repeatable-read snapshot.
    // Consumer commit changes available and unfinished together. Closing a message can
    // only INCREASE this budget. Terminal replay/reset is intentionally prohibited.
    @Select("""
        SELECT
          CASE WHEN #{segmented} AND seg.n > 0 THEN seg.total ELSE s.total_stock END AS total,
          CASE WHEN #{segmented} AND seg.n > 0 THEN seg.available ELSE s.available_stock END AS available,
          CASE WHEN #{segmented} AND seg.n > 0 THEN seg.sold ELSE s.sold_count END AS sold,
          (SELECT COUNT(*) FROM seckill_order o WHERE o.activity_id = s.activity_id
             AND o.sku_id = s.sku_id AND o.status = 2) AS successful,
          (SELECT COUNT(*) FROM seckill_message m WHERE m.activity_id = s.activity_id
             AND m.sku_id = s.sku_id AND m.status IN (0,1,3,4,5,8,9,10)
             AND NOT EXISTS (SELECT 1 FROM seckill_order o WHERE o.activity_id = m.activity_id
               AND o.sku_id = m.sku_id AND o.user_id = m.user_id)) AS unfinished
        FROM sku_stock s
        LEFT JOIN (SELECT activity_id, sku_id, COUNT(*) AS n, SUM(total_stock) AS total,
                     SUM(available_stock) AS available, SUM(sold_count) AS sold
                   FROM sku_stock_segment WHERE activity_id = #{activityId} AND sku_id = #{skuId}
                   GROUP BY activity_id, sku_id) seg
          ON seg.activity_id = s.activity_id AND seg.sku_id = s.sku_id
        WHERE s.activity_id = #{activityId} AND s.sku_id = #{skuId}
        """)
    @ConstructorArgs({
        @Arg(column = "total", javaType = long.class), @Arg(column = "available", javaType = long.class),
        @Arg(column = "sold", javaType = long.class), @Arg(column = "successful", javaType = long.class),
        @Arg(column = "unfinished", javaType = long.class)
    })
    StockFacts snapshot(@Param("activityId") Long activityId, @Param("skuId") Long skuId,
                        @Param("segmented") boolean segmented);
}
