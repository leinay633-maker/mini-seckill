package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.miniseckill.common.ActivityStatus;
import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.AdmissionCapacityProperties;
import com.example.miniseckill.entity.SeckillActivity;
import com.example.miniseckill.mapper.SeckillActivityMapper;
import com.example.miniseckill.service.SeckillMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ActivityAdmissionCacheTest {
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-09T10:00:00Z"));
    private final AtomicReference<SeckillActivity> row = new AtomicReference<>();
    private final SeckillActivityMapper mapper = mock(SeckillActivityMapper.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AdmissionCapacityProperties properties = new AdmissionCapacityProperties();
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
        public Instant instant() { return now.get(); }
    };
    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        row.set(activity(ActivityStatus.RUNNING));
        when(mapper.selectByActivityId(1L)).thenAnswer(invocation -> row.get());
        when(mapper.updateStatus(eq(1L), anyInt())).thenAnswer(invocation -> {
            row.set(activity(ActivityStatus.fromCode(invocation.getArgument(1))));
            return 1;
        });
        service = newService();
    }

    private ActivityServiceImpl newService() {
        return new ActivityServiceImpl(mapper, properties, new SeckillMetrics(registry), nanos::get, clock);
    }

    @Test
    void tokenAndOrderChecksShareSnapshotButQueryRemainsAuthoritative() {
        service.assertRunning(1L);
        service.assertRunning(1L);
        verify(mapper, times(1)).selectByActivityId(1L);
        service.query(1L);
        verify(mapper, times(2)).selectByActivityId(1L);
        assertEquals(1, registry.get("seckill_capacity_stage").tag("stage", "activity_lookup").timer().count());
    }

    @Test
    void closeInvalidatesOnThisInstanceWithoutWaitingForTtl() {
        service.assertRunning(1L);
        service.close(1L);
        assertRejected(403);
        assertEquals(0, nanos.get());
    }

    @Test
    void startInvalidatesNotStartedSnapshot() {
        row.set(activity(ActivityStatus.NOT_STARTED));
        assertRejected(403);
        service.start(1L);
        assertDoesNotThrow(() -> service.assertRunning(1L));
    }

    @Test
    void createInvalidatesNegativeSnapshot() {
        row.set(null);
        assertRejected(404);
        when(mapper.upsert(eq(1L), anyString(), anyInt(), any(), any())).thenAnswer(invocation -> {
            row.set(activity(ActivityStatus.NOT_STARTED));
            return 1;
        });
        service.create(1L, "test", LocalDateTime.now(clock).minusHours(1), LocalDateTime.now(clock).plusHours(1));
        assertRejected(403);
    }

    @Test
    void naturalEndIsRecheckedOnAHit() {
        row.get().setEndTime(LocalDateTime.now(clock).plusNanos(100_000_000));
        service.assertRunning(1L);
        now.updateAndGet(value -> value.plusMillis(101));
        assertRejected(403);
        verify(mapper, times(1)).selectByActivityId(1L);
    }

    @Test
    void naturalStartIsRecheckedOnAHit() {
        row.get().setStartTime(LocalDateTime.now(clock).plusNanos(100_000_000));
        assertRejected(403);
        now.updateAndGet(value -> value.plusMillis(100));
        assertDoesNotThrow(() -> service.assertRunning(1L));
        verify(mapper, times(1)).selectByActivityId(1L);
    }

    @Test
    void externalCloseHasABoundedSnapshotWindowNotImmediateInvalidation() {
        service.assertRunning(1L);
        row.set(activity(ActivityStatus.CLOSED)); // Another instance or direct SQL, not a local command.
        nanos.set(Duration.ofMillis(249).toNanos());
        assertDoesNotThrow(() -> service.assertRunning(1L));
        nanos.set(Duration.ofMillis(250).toNanos());
        assertRejected(403);
    }

    @Test
    void readLatencyConsumesTtlInsteadOfStartingANewTtlAtCompletion() {
        AtomicBoolean first = new AtomicBoolean(true);
        when(mapper.selectByActivityId(1L)).thenAnswer(invocation -> {
            SeckillActivity snapshot = row.get();
            if (first.getAndSet(false)) {
                nanos.set(Duration.ofMillis(200).toNanos());
            }
            return snapshot;
        });
        service.assertRunning(1L);
        row.set(activity(ActivityStatus.CLOSED));
        nanos.set(Duration.ofMillis(251).toNanos());
        assertRejected(403);
        verify(mapper, times(2)).selectByActivityId(1L);
    }

    @Test
    void slowReadOlderThanTtlIsNotCached() {
        AtomicBoolean first = new AtomicBoolean(true);
        when(mapper.selectByActivityId(1L)).thenAnswer(invocation -> {
            SeckillActivity snapshot = row.get();
            if (first.getAndSet(false)) {
                nanos.set(Duration.ofSeconds(1).toNanos());
                row.set(activity(ActivityStatus.CLOSED));
            }
            return snapshot;
        });
        service.assertRunning(1L); // This already-in-flight SQL read is not revoked.
        assertRejected(403);
        verify(mapper, times(2)).selectByActivityId(1L);
    }

    @Test
    void databaseFailureAfterExpiryDoesNotServeStaleRunning() {
        service.assertRunning(1L);
        nanos.set(Duration.ofMillis(250).toNanos());
        when(mapper.selectByActivityId(1L)).thenThrow(new IllegalStateException("database unavailable"));
        assertThrows(IllegalStateException.class, () -> service.assertRunning(1L));
        assertThrows(IllegalStateException.class, () -> service.assertRunning(1L));
    }

    @Test
    void zeroTtlRestoresOneQueryPerCheck() {
        properties.setActivityCacheTtl(Duration.ZERO);
        service = newService();
        service.assertRunning(1L);
        row.set(activity(ActivityStatus.CLOSED));
        assertRejected(403);
        verify(mapper, times(2)).selectByActivityId(1L);
    }

    @Test
    void concurrentColdReadsCoalesceToOneDatabaseLoad() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(mapper.selectByActivityId(1L)).thenAnswer(invocation -> {
            loading.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return row.get();
        });
        try {
            List<Future<?>> checks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                checks.add(pool.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    service.assertRunning(1L);
                    return null;
                }));
            }
            start.countDown();
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (Future<?> check : checks) {
                check.get(5, TimeUnit.SECONDS);
            }
            verify(mapper, times(1)).selectByActivityId(1L);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void closeRacingWithColdReadCannotLeaveAnOldSnapshotAfterCloseReturns() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(mapper.selectByActivityId(1L)).thenAnswer(invocation -> {
            SeckillActivity snapshot = row.get();
            if (first.getAndSet(false)) {
                loading.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
            }
            return snapshot;
        });
        try {
            Future<?> load = pool.submit(() -> service.assertRunning(1L));
            assertTrue(loading.await(5, TimeUnit.SECONDS));
            Future<?> close = pool.submit(() -> {
                closeStarted.countDown();
                service.close(1L);
            });
            assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
            release.countDown();
            load.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertRejected(403);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void ambiguousLifecycleWriteAlsoInvalidatesCache() {
        service.assertRunning(1L);
        when(mapper.updateStatus(1L, ActivityStatus.CLOSED.getCode())).thenAnswer(invocation -> {
            row.set(activity(ActivityStatus.CLOSED));
            throw new IllegalStateException("connection lost after write");
        });
        assertThrows(IllegalStateException.class, () -> service.close(1L));
        assertRejected(403);
    }

    @Test
    void transactionCompletionEvictsAnyPreCommitRepopulation() {
        service.assertRunning(1L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.close(1L);
            row.set(activity(ActivityStatus.RUNNING)); // A simulated pre-commit reader sees the old row.
            service.assertRunning(1L);
            row.set(activity(ActivityStatus.CLOSED));
            for (TransactionSynchronization callback : TransactionSynchronizationManager.getSynchronizations()) {
                callback.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
            assertRejected(403);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void enclosingTransactionDoesNotReadOrPublishSharedSnapshots() {
        row.set(activity(ActivityStatus.CLOSED));
        assertRejected(403);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            row.set(activity(ActivityStatus.RUNNING)); // Visible only to a simulated enclosing transaction.
            assertDoesNotThrow(() -> service.assertRunning(1L));
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        row.set(activity(ActivityStatus.CLOSED)); // Rollback: shared cache must not contain RUNNING.
        assertRejected(403);
        verify(mapper, times(2)).selectByActivityId(1L);
    }

    private void assertRejected(int code) {
        assertEquals(code, assertThrows(BusinessException.class, () -> service.assertRunning(1L)).getCode());
    }

    private SeckillActivity activity(ActivityStatus status) {
        SeckillActivity activity = new SeckillActivity();
        activity.setActivityId(1L);
        activity.setName("test");
        activity.setStatus(status.getCode());
        activity.setStartTime(LocalDateTime.now(clock).minusHours(1));
        activity.setEndTime(LocalDateTime.now(clock).plusHours(1));
        return activity;
    }
}
