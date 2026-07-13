package com.example.miniseckill.mapper;

import com.example.miniseckill.entity.SeckillMessageRecord;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Mapper for local message table used by reliable MQ send and retry.
 */
@Mapper
public interface SeckillMessageMapper {

    @Insert("""
            INSERT INTO seckill_message (request_id, activity_id, user_id, sku_id, status, retry_count, created_at, updated_at)
            VALUES (#{requestId}, #{activityId}, #{userId}, #{skuId}, #{status}, 0, NOW(), NOW())
            """)
    int insertPending(@Param("requestId") String requestId,
                      @Param("activityId") Long activityId,
                      @Param("userId") Long userId,
                      @Param("skuId") Long skuId,
                      @Param("status") int status);

    @Update("""
            UPDATE seckill_message
            SET status = #{status},
                last_error = NULL,
                next_retry_at = NULL,
                updated_at = NOW()
            WHERE request_id = #{requestId}
            """)
    int updateStatus(@Param("requestId") String requestId, @Param("status") int status);

    @Update("""
            UPDATE seckill_message
            SET status = #{sendingStatus},
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status = #{sendingStatus}
            """)
    int markSending(@Param("requestId") String requestId,
                    @Param("sendingStatus") int sendingStatus,
                    @Param("consumedStatus") int consumedStatus,
                    @Param("timeoutStatus") int timeoutStatus,
                    @Param("deadStatus") int deadStatus,
                    @Param("consumingStatus") int consumingStatus);

    @Update("""
            UPDATE seckill_message
            SET status = #{sentStatus},
                last_error = NULL,
                next_retry_at = NULL,
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status NOT IN (#{consumedStatus}, #{timeoutStatus}, #{deadStatus}, #{consumingStatus})
            """)
    int markSentFromSending(@Param("requestId") String requestId,
                            @Param("sentStatus") int sentStatus,
                            @Param("sendingStatus") int sendingStatus);

    @Update("""
            UPDATE seckill_message
            SET status = #{consumingStatus},
                last_error = NULL,
                next_retry_at = NULL,
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status IN (#{sentStatus}, #{sendingStatus}, #{replayedStatus})
            """)
    int markConsuming(@Param("requestId") String requestId,
                      @Param("consumingStatus") int consumingStatus,
                      @Param("sentStatus") int sentStatus,
                      @Param("sendingStatus") int sendingStatus,
                      @Param("replayedStatus") int replayedStatus);

    @Update("""
            UPDATE seckill_message
            SET status = #{consumedStatus},
                last_error = NULL,
                next_retry_at = NULL,
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status = #{consumingStatus}
            """)
    int markConsumedFromConsuming(@Param("requestId") String requestId,
                                  @Param("consumedStatus") int consumedStatus,
                                  @Param("consumingStatus") int consumingStatus);

    @Update("""
            UPDATE seckill_message
            SET status = #{failedStatus},
                retry_count = retry_count + 1,
                last_error = #{lastError},
                next_retry_at = #{nextRetryAt},
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status = #{consumingStatus}
            """)
    int markFailedFromConsuming(@Param("requestId") String requestId,
                                @Param("failedStatus") int failedStatus,
                                @Param("consumingStatus") int consumingStatus,
                                @Param("lastError") String lastError,
                                @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("""
            UPDATE seckill_message
            SET status = #{status},
                retry_count = retry_count + 1,
                last_error = #{lastError},
                next_retry_at = NOW(),
                updated_at = NOW()
            WHERE request_id = #{requestId}
            """)
    int markFailed(@Param("requestId") String requestId,
                   @Param("status") int status,
                   @Param("lastError") String lastError);

    @Update("""
            UPDATE seckill_message
            SET status = #{failedStatus},
                retry_count = retry_count + 1,
                last_error = #{lastError},
                next_retry_at = NOW(),
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status = #{sendingStatus}
            """)
    int markPublishFailedFromSending(@Param("requestId") String requestId,
                                     @Param("failedStatus") int failedStatus,
                                     @Param("sendingStatus") int sendingStatus,
                                     @Param("lastError") String lastError);

    @Update("""
            UPDATE seckill_message
            SET status = #{status},
                retry_count = retry_count + 1,
                last_error = #{lastError},
                next_retry_at = #{nextRetryAt},
                updated_at = NOW()
            WHERE request_id = #{requestId}
            """)
    int markFailedForRetry(@Param("requestId") String requestId,
                           @Param("status") int status,
                           @Param("lastError") String lastError,
                           @Param("nextRetryAt") LocalDateTime nextRetryAt);

    @Update("""
            UPDATE seckill_message
            SET status = #{deadStatus},
                last_error = #{lastError},
                dead_at = NOW(),
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status <> #{consumedStatus}
            """)
    int markDead(@Param("requestId") String requestId,
                 @Param("deadStatus") int deadStatus,
                 @Param("consumedStatus") int consumedStatus,
                 @Param("lastError") String lastError);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status IN (#{pendingStatus}, #{sendingStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus})
              AND retry_count < #{maxRetry}
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<SeckillMessageRecord> selectRetryable(@Param("pendingStatus") int pendingStatus,
                                               @Param("sendingStatus") int sendingStatus,
                                               @Param("failedStatus") int failedStatus,
                                               @Param("confirmFailedStatus") int confirmFailedStatus,
                                               @Param("returnedStatus") int returnedStatus,
                                               @Param("maxRetry") int maxRetry,
                                               @Param("limit") int limit);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status IN (#{pendingStatus}, #{sendingStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus})
              AND retry_count >= #{maxRetry}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<SeckillMessageRecord> selectRetryExhausted(@Param("pendingStatus") int pendingStatus,
                                                    @Param("sendingStatus") int sendingStatus,
                                                    @Param("failedStatus") int failedStatus,
                                                    @Param("confirmFailedStatus") int confirmFailedStatus,
                                                    @Param("returnedStatus") int returnedStatus,
                                                    @Param("maxRetry") int maxRetry,
                                                    @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM seckill_message
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
              AND status IN (#{pendingStatus}, #{sendingStatus}, #{sentStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus}, #{consumingStatus})
              AND retry_count < #{maxRetry}
            """)
    long countUnfinishedByActivitySku(@Param("activityId") Long activityId,
                                      @Param("skuId") Long skuId,
                                      @Param("pendingStatus") int pendingStatus,
                                      @Param("sendingStatus") int sendingStatus,
                                      @Param("sentStatus") int sentStatus,
                                      @Param("failedStatus") int failedStatus,
                                      @Param("confirmFailedStatus") int confirmFailedStatus,
                                      @Param("returnedStatus") int returnedStatus,
                                      @Param("consumingStatus") int consumingStatus,
                                      @Param("maxRetry") int maxRetry);

    @Select("""
            SELECT COUNT(*)
            FROM seckill_message
            WHERE activity_id = #{activityId}
              AND sku_id = #{skuId}
              AND status IN (#{pendingStatus}, #{sendingStatus}, #{sentStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus}, #{consumingStatus})
            """)
    long countRecoveringUnfinishedByActivitySku(@Param("activityId") Long activityId,
                                                @Param("skuId") Long skuId,
                                                @Param("pendingStatus") int pendingStatus,
                                                @Param("sendingStatus") int sendingStatus,
                                                @Param("sentStatus") int sentStatus,
                                                @Param("failedStatus") int failedStatus,
                                                @Param("confirmFailedStatus") int confirmFailedStatus,
                                                @Param("returnedStatus") int returnedStatus,
                                                @Param("consumingStatus") int consumingStatus);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status = #{consumingStatus}
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<SeckillMessageRecord> selectStaleConsuming(@Param("consumingStatus") int consumingStatus,
                                                    @Param("cutoff") LocalDateTime cutoff,
                                                    @Param("limit") int limit);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status IN (#{pendingStatus}, #{sendingStatus}, #{sentStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus})
              AND updated_at < #{cutoff}
            ORDER BY updated_at ASC
            LIMIT #{limit}
            """)
    List<SeckillMessageRecord> selectTimeoutCandidates(@Param("pendingStatus") int pendingStatus,
                                                       @Param("sendingStatus") int sendingStatus,
                                                       @Param("sentStatus") int sentStatus,
                                                       @Param("failedStatus") int failedStatus,
                                                       @Param("confirmFailedStatus") int confirmFailedStatus,
                                                       @Param("returnedStatus") int returnedStatus,
                                                       @Param("cutoff") LocalDateTime cutoff,
                                                       @Param("limit") int limit);

    @Update("""
            UPDATE seckill_message
            SET status = #{timeoutStatus},
                last_error = #{lastError},
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status IN (#{pendingStatus}, #{sendingStatus}, #{sentStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus})
            """)
    int markTimeout(@Param("requestId") String requestId,
                    @Param("timeoutStatus") int timeoutStatus,
                    @Param("pendingStatus") int pendingStatus,
                    @Param("sendingStatus") int sendingStatus,
                    @Param("sentStatus") int sentStatus,
                    @Param("failedStatus") int failedStatus,
                    @Param("confirmFailedStatus") int confirmFailedStatus,
                    @Param("returnedStatus") int returnedStatus,
                    @Param("lastError") String lastError);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE request_id = #{requestId}
            LIMIT 1
            """)
    SeckillMessageRecord selectByRequestId(@Param("requestId") String requestId);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status = #{deadStatus}
            ORDER BY dead_at ASC, updated_at ASC
            LIMIT #{limit}
            """)
    List<SeckillMessageRecord> selectDead(@Param("deadStatus") int deadStatus, @Param("limit") int limit);

    @Update("""
            UPDATE seckill_message
            SET status = #{replayedStatus},
                next_retry_at = NULL,
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status IN (#{deadStatus}, #{timeoutStatus}, #{failedStatus}, #{returnedStatus}, #{confirmFailedStatus})
            """)
    int markReplayed(@Param("requestId") String requestId,
                     @Param("replayedStatus") int replayedStatus,
                     @Param("deadStatus") int deadStatus,
                     @Param("timeoutStatus") int timeoutStatus,
                     @Param("failedStatus") int failedStatus,
                     @Param("returnedStatus") int returnedStatus,
                     @Param("confirmFailedStatus") int confirmFailedStatus);
}
