package com.example.miniseckill.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for segmented MySQL stock deduction to reduce one-row hot updates.
 */
@Mapper
public interface SkuStockSegmentMapper {

    @Delete("""
            DELETE FROM sku_stock_segment
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            """)
    int deleteByActivitySku(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Insert("""
            INSERT INTO sku_stock_segment (activity_id, sku_id, segment_id, total_stock, available_stock, sold_count, created_at, updated_at)
            VALUES (#{activityId}, #{skuId}, #{segmentId}, #{stock}, #{stock}, 0, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                total_stock = VALUES(total_stock),
                available_stock = VALUES(available_stock),
                sold_count = 0,
                updated_at = NOW()
            """)
    int upsertSegment(@Param("activityId") Long activityId,
                      @Param("skuId") Long skuId,
                      @Param("segmentId") int segmentId,
                      @Param("stock") int stock);

    @Update("""
            UPDATE sku_stock_segment
            SET available_stock = available_stock - 1,
                sold_count = sold_count + 1,
                updated_at = NOW()
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
              AND segment_id = #{segmentId}
              AND available_stock > 0
            """)
    int decreaseSegmentStock(@Param("activityId") Long activityId,
                             @Param("skuId") Long skuId,
                             @Param("segmentId") int segmentId);

    @Update("""
            UPDATE sku_stock_segment
            SET available_stock = available_stock - 1,
                sold_count = sold_count + 1,
                updated_at = NOW()
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
              AND available_stock > 0
            ORDER BY available_stock DESC, segment_id ASC
            LIMIT 1
            """)
    int decreaseAnySegmentStock(@Param("activityId") Long activityId,
                                @Param("skuId") Long skuId);

    @Select("""
            SELECT COALESCE(SUM(total_stock), 0)
            FROM sku_stock_segment
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            """)
    int sumTotalStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Select("""
            SELECT COALESCE(SUM(available_stock), 0)
            FROM sku_stock_segment
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            """)
    int sumAvailableStock(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Select("""
            SELECT COALESCE(SUM(sold_count), 0)
            FROM sku_stock_segment
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            """)
    int sumSoldCount(@Param("activityId") Long activityId, @Param("skuId") Long skuId);

    @Select("""
            SELECT COUNT(*)
            FROM sku_stock_segment
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
            """)
    int countSegments(@Param("activityId") Long activityId, @Param("skuId") Long skuId);
}
