package com.example.miniseckill.mapper;

import com.example.miniseckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for creating and querying seckill orders.
 */
@Mapper
public interface SeckillOrderMapper {

    @Insert("""
            INSERT INTO seckill_order (order_id, activity_id, user_id, sku_id, status, created_at, updated_at)
            VALUES (#{orderId}, #{activityId}, #{userId}, #{skuId}, #{status}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SeckillOrder order);

    @Select("""
            SELECT id, order_id, activity_id, user_id, sku_id, status, created_at, updated_at
            FROM seckill_order
            WHERE activity_id = #{activityId}
              AND user_id = #{userId}
              AND sku_id = #{skuId}
            LIMIT 1
            """)
    SeckillOrder selectByUserSku(@Param("activityId") Long activityId,
                                  @Param("userId") Long userId,
                                  @Param("skuId") Long skuId);

    @Select("""
            SELECT COUNT(*)
            FROM seckill_order
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
              AND status = #{status}
            """)
    long countByActivitySkuStatus(@Param("activityId") Long activityId,
                                  @Param("skuId") Long skuId,
                                  @Param("status") int status);
}
