package com.example.miniseckill.mapper;

import com.example.miniseckill.entity.SkuStock;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;

/**
 * Mapper for MySQL stock initialization and guarded stock deduction.
 */
@Mapper
public interface SkuStockMapper {

    @Insert("""
            INSERT INTO sku_stock (activity_id, sku_id, total_stock, available_stock, sold_count, created_at, updated_at)
            VALUES (#{activityId}, #{skuId}, #{stock}, #{stock}, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                total_stock = VALUES(total_stock),
                available_stock = VALUES(available_stock),
                sold_count = 0,
                updated_at = NOW()
            """)
    int upsertStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId, @Param("stock") Integer stock);

    @Update("""
            UPDATE sku_stock
            SET available_stock = available_stock - 1,
                sold_count = sold_count + 1,
                updated_at = NOW()
            WHERE sku_id = #{skuId}
              AND activity_id = #{activityId}
              AND available_stock > 0
            """)
    int decreaseStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Select("""
            SELECT id, activity_id, sku_id, total_stock, available_stock, sold_count, created_at, updated_at
            FROM sku_stock
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            LIMIT 1
            """)
    SkuStock selectBySkuId(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Select("""
            SELECT id, activity_id, sku_id, total_stock, available_stock, sold_count, created_at, updated_at
            FROM sku_stock
            WHERE id > #{lastId}
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<SkuStock> selectPageAfterId(@Param("lastId") long lastId, @Param("limit") int limit);

    @Select("""
            SELECT id, activity_id, sku_id, total_stock, available_stock, sold_count, created_at, updated_at
            FROM sku_stock
            ORDER BY updated_at DESC
            LIMIT #{limit}
            """)
    List<SkuStock> selectRecentStocks(@Param("limit") int limit);

    @Update("""
            UPDATE sku_stock s
            JOIN (
                SELECT activity_id,
                       sku_id,
                       COALESCE(SUM(total_stock), 0) AS total_stock,
                       COALESCE(SUM(available_stock), 0) AS available_stock,
                       COALESCE(SUM(sold_count), 0) AS sold_count
                FROM sku_stock_segment
                WHERE activity_id = #{activityId}
                  AND sku_id = #{skuId}
                GROUP BY activity_id, sku_id
            ) seg ON s.activity_id = seg.activity_id AND s.sku_id = seg.sku_id
            SET s.total_stock = seg.total_stock,
                s.available_stock = seg.available_stock,
                s.sold_count = seg.sold_count,
                s.updated_at = NOW()
            WHERE s.activity_id = #{activityId}
              AND s.sku_id = #{skuId}
            """)
    int syncFromSegments(@Param("activityId") Long activityId, @Param("skuId") Long skuId);
}
