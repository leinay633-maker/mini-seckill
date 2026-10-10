package com.example.miniseckill.job;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.entity.SeckillMessageRecord;
import com.example.miniseckill.mapper.*;
import com.example.miniseckill.mq.SeckillProducer;
import com.example.miniseckill.service.SeckillMetrics;
import java.util.List;
import org.junit.jupiter.api.Test;
class SeckillMessageRetryJobTest {
    final SeckillMessageMapper messages=mock(SeckillMessageMapper.class);final CompensationRecordMapper audit=mock(CompensationRecordMapper.class);final SeckillProducer producer=mock(SeckillProducer.class);final SeckillProperties p=new SeckillProperties();final SeckillMetrics metrics=mock(SeckillMetrics.class);
    final SeckillMessageRetryJob job=new SeckillMessageRetryJob(messages,audit,producer,p,metrics);
    SeckillMessageRecord row(){var r=new SeckillMessageRecord();r.setRequestId("r");r.setActivityId(1L);r.setUserId(7L);r.setSkuId(1001L);r.setRetryCount(0);return r;}
    void retry(){when(messages.selectRetryable(anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(List.of(row()));}
    void exhausted(){when(messages.selectRetryExhausted(anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt())).thenReturn(List.of(row()));}
    @Test void onlyWinningAttemptReportsPublication(){retry();when(producer.send(any())).thenReturn(true);job.retrySendMessage();verify(metrics).mq("retry_published");}
    @Test void losingClaimDoesNotCountPublication(){retry();job.retrySendMessage();verify(metrics,never()).mq("retry_published");}
    @Test void publishFailureCannotDoASecondStatusOnlyUpdate(){retry();when(producer.send(any())).thenThrow(new IllegalStateException("transport"));job.retrySendMessage();verify(messages,never()).markFailedForRetry(any(),anyInt(),any(),any());verify(metrics,never()).mq("retry_published");}
    @Test void exhaustionSideEffectsRequireExpiredLeaseAndWinningDatabaseCas(){exhausted();when(messages.exhaustSend("r",5)).thenReturn(1);job.retrySendMessage();verify(audit).insert(any());verify(metrics).mq("retry_exhausted_dead");}
    @Test void staleExhaustionSnapshotCannotEmitFalseAudit(){exhausted();job.retrySendMessage();verifyNoInteractions(audit);verify(metrics,never()).mq("retry_exhausted_dead");}
}
