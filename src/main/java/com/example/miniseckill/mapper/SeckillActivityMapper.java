package com.example.miniseckill.mapper;

import com.example.miniseckill.entity.SeckillActivity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for creating and changing seckill activity lifecycle state.
 */
@Mapper
public interface SeckillActivityMapper {

    @Insert("""
            INSERT INTO seckill_activity (activity_id, name, status, start_time, end_time, created_at, updated_at)
            VALUES (#{activityId}, #{name}, #{status}, #{startTime}, #{endTime}, NOW(), NOW())
            ON DUPLICATE KEY UPDATE
                name = VALUES(name),
                status = VALUES(status),
                start_time = VALUES(start_time),
                end_time = VALUES(end_time),
                updated_at = NOW()
            """)
    int upsert(@Param("activityId") Long activityId,
               @Param("name") String name,
               @Param("status") int status,
               @Param("startTime") LocalDateTime startTime,
               @Param("endTime") LocalDateTime endTime);

    @Update("""
            UPDATE seckill_activity
            SET status = #{status},
                updated_at = NOW()
            WHERE activity_id = #{activityId}
            """)
    int updateStatus(@Param("activityId") Long activityId, @Param("status") int status);

    @Select("""
            SELECT id, activity_id, name, status, start_time, end_time, created_at, updated_at
            FROM seckill_activity
            WHERE activity_id = #{activityId}
            LIMIT 1
            """)
    SeckillActivity selectByActivityId(@Param("activityId") Long activityId);
}
