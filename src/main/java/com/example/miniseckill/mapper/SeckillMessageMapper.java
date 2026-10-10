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
 * Numeric allowlists below are the persisted MessageStatus contract, covered by
 * MySQL integration tests. Stale retry snapshots must not overwrite terminal rows.
 */
@Mapper
public interface SeckillMessageMapper {

    @Insert("""
            INSERT INTO seckill_message (request_id, activity_id, user_id, sku_id, status, retry_count, send_token, send_lease_until, created_at, updated_at)
            VALUES (#{requestId}, #{activityId}, #{userId}, #{skuId}, #{status}, 0,
                    CASE WHEN #{status} = 9 THEN #{requestId} ELSE NULL END,
                    CASE WHEN #{status} = 9 THEN DATE_ADD(NOW(6), INTERVAL 20 SECOND) ELSE NULL END, NOW(), NOW())
            """)
    int insertPending(@Param("requestId") String requestId,
                      @Param("activityId") Long activityId,
                      @Param("userId") Long userId,
                      @Param("skuId") Long skuId,
                      @Param("status") int status);

    // A cancellation tombstone is inserted only by the uncertain-admission resolver. The
    // unique request_id race serializes with a delayed/ambiguous real INSERT; never delete it online.
    @Insert("""
            INSERT INTO seckill_message (request_id, activity_id, user_id, sku_id, status, retry_count, last_error, created_at, updated_at)
            VALUES (#{requestId}, #{activityId}, #{userId}, #{skuId}, 11, 0, 'admission cancelled before durable acceptance', NOW(), NOW())
            ON DUPLICATE KEY UPDATE request_id = VALUES(request_id)
            """)
    int insertAdmissionCancellation(@Param("requestId") String requestId, @Param("activityId") Long activityId,
            @Param("userId") Long userId, @Param("skuId") Long skuId);

    // Legacy synchronous completion: never take over an active consumer or a terminal row.
    @Update("""
            UPDATE seckill_message
            SET status = #{status},
                last_error = NULL,
                next_retry_at = NULL,
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status IN (0, 1, 3, 4, 5, 8, 9)
            """)
    int updateStatus(@Param("requestId") String requestId, @Param("status") int status);

    @Update("""
            UPDATE seckill_message
            SET status = #{sendingStatus},
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status IN (0, 3, 4, 5, 8, 9)
              AND status NOT IN (#{consumedStatus}, #{timeoutStatus}, #{deadStatus}, #{consumingStatus})
            """)
    int markSending(@Param("requestId") String requestId,
                    @Param("sendingStatus") int sendingStatus,
                    @Param("consumedStatus") int consumedStatus,
                    @Param("timeoutStatus") int timeoutStatus,
                    @Param("deadStatus") int deadStatus,
                    @Param("consumingStatus") int consumingStatus);

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
              AND status IN (0, 3, 4, 5, 9)
            """)
    int markFailed(@Param("requestId") String requestId,
                   @Param("status") int status,
                   @Param("lastError") String lastError);

    @Update("""
            UPDATE seckill_message
            SET status = #{status},
                retry_count = retry_count + 1,
                last_error = #{lastError},
                next_retry_at = #{nextRetryAt},
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status IN (0, 3, 4, 5, 9)
            """)
    int markFailedForRetry(@Param("requestId") String requestId,
                           @Param("status") int status,
                           @Param("lastError") String lastError,
                           @Param("nextRetryAt") LocalDateTime nextRetryAt);

    // Retry exhaustion owns only send-side states, not a consumer that won after the scan.
    @Update("""
            UPDATE seckill_message
            SET status = #{deadStatus},
                last_error = #{lastError},
                dead_at = NOW(),
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status <> #{consumedStatus}
              AND status IN (0, 3, 4, 5, 9)
            """)
    int markDead(@Param("requestId") String requestId,
                 @Param("deadStatus") int deadStatus,
                 @Param("consumedStatus") int consumedStatus,
                 @Param("lastError") String lastError);

    @Update("""
            UPDATE seckill_message
            SET status = #{deadStatus},
                last_error = #{lastError},
                next_retry_at = NULL,
                dead_at = NOW(),
                updated_at = NOW()
            WHERE request_id = #{requestId}
              AND status = #{consumingStatus}
            """)
    int markDeadFromConsuming(@Param("requestId") String requestId,
                              @Param("deadStatus") int deadStatus,
                              @Param("consumingStatus") int consumingStatus,
                              @Param("lastError") String lastError);

    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count, last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status IN (#{pendingStatus}, #{sendingStatus}, #{failedStatus}, #{confirmFailedStatus}, #{returnedStatus})
              AND retry_count < #{maxRetry}
              AND (send_lease_until IS NULL OR send_lease_until <= NOW(6))
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
              AND (send_lease_until IS NULL OR send_lease_until <= NOW(6))
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
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

    // Candidate timing and mutation use the same database clock. A scan is
    // still only a hint; timeoutIfStale rechecks after acquiring the row lock.
    @Select("""
            SELECT id, request_id, activity_id, user_id, sku_id, status, retry_count,
                   last_error, next_retry_at, dead_at, created_at, updated_at
            FROM seckill_message
            WHERE status IN (0,1,3,4,5,8,9)
              AND updated_at < DATE_SUB(NOW(6), INTERVAL #{timeoutMicros} MICROSECOND)
              AND (send_lease_until IS NULL OR send_lease_until <= NOW(6))
            ORDER BY updated_at ASC, id ASC LIMIT #{limit}
            """)
    List<SeckillMessageRecord> selectTimeoutDue(@Param("timeoutMicros") long timeoutMicros,
                                               @Param("limit") int limit);


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
            UPDATE seckill_message SET status=9, send_token=#{token},
              send_lease_until=DATE_ADD(NOW(6), INTERVAL 20 SECOND),
              retry_count=retry_count+1, next_retry_at=NULL, updated_at=NOW()
            WHERE request_id=#{requestId} AND status IN (0,3,4,5,8,9)
              AND retry_count < #{maxRetry}
              AND (send_lease_until IS NULL OR send_lease_until <= NOW(6))
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            """)
    int claimSend(@Param("requestId") String requestId, @Param("token") String token, @Param("maxRetry") int maxRetry);

    @Update("""
            UPDATE seckill_message SET status=#{status}, last_error=#{error}, updated_at=NOW(),
              next_retry_at=CASE WHEN #{status}=1 THEN NULL ELSE DATE_ADD(NOW(), INTERVAL 5 SECOND) END,
              send_lease_until=NULL
            WHERE request_id=#{requestId} AND status=9 AND send_token=#{token} AND send_lease_until > NOW(6)
            """)
    int finishSend(@Param("requestId") String requestId, @Param("token") String token,
                   @Param("status") int status, @Param("error") String error);

    @Update("""
            UPDATE seckill_message SET status=7, last_error='message retry exhausted',
              dead_at=NOW(), updated_at=NOW(), send_token=NULL, send_lease_until=NULL
            WHERE request_id=#{requestId} AND status IN (0,3,4,5,8,9)
              AND retry_count >= #{maxRetry}
              AND (send_lease_until IS NULL OR send_lease_until <= NOW(6))
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            """)
    int exhaustSend(@Param("requestId") String requestId, @Param("maxRetry") int maxRetry);

    @Update("""
            UPDATE seckill_message SET status=6, last_error='queued order timeout', updated_at=NOW(),
              send_token=NULL, send_lease_until=NULL
            WHERE request_id=#{requestId} AND status IN (0,1,3,4,5,8,9)
              AND updated_at < DATE_SUB(NOW(6), INTERVAL #{timeoutMicros} MICROSECOND)
              AND (send_lease_until IS NULL OR send_lease_until <= NOW(6))
            """)
    int timeoutIfStale(@Param("requestId") String requestId, @Param("timeoutMicros") long timeoutMicros);
}
