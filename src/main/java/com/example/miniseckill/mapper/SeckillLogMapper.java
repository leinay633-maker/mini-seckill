package com.example.miniseckill.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper for request logs used in observability and compensation explanation.
 */
@Mapper
public interface SeckillLogMapper {

    @Insert("""
            INSERT INTO seckill_log (request_id, activity_id, user_id, sku_id, result, created_at)
            VALUES (#{requestId}, #{activityId}, #{userId}, #{skuId}, #{result}, NOW())
            """)
    int insertLog(@Param("requestId") String requestId,
                  @Param("activityId") Long activityId,
                  @Param("userId") Long userId,
                  @Param("skuId") Long skuId,
                  @Param("result") String result);
}
