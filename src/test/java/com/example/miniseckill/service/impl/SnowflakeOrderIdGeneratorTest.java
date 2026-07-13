package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.service.SeckillMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeOrderIdGeneratorTest {

    private SnowflakeOrderIdGenerator generator(long workerId) {
        SeckillProperties props = new SeckillProperties();
        props.getSnowflake().setWorkerId(workerId);
        return new SnowflakeOrderIdGenerator(props, new SeckillMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void singleThreadIdsAreUniqueAndMonotonic() {
        SnowflakeOrderIdGenerator gen = generator(1);
        long previous = -1L;
        Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            long id = gen.nextId();
            assertTrue(id > previous, "id must be strictly increasing on a single thread");
            assertTrue(seen.add(id), "id must be unique");
            previous = id;
        }
    }

    @Test
    void concurrentGenerationProducesNoDuplicates() throws InterruptedException {
        SnowflakeOrderIdGenerator gen = generator(7);
        int threads = 100;
        int perThread = 10_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Long> ids = ConcurrentHashMap.newKeySet(threads * perThread);
        AtomicLong duplicates = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        if (!ids.add(gen.nextId())) {
                            duplicates.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "generation should finish in time");
        pool.shutdownNow();

        assertEquals(0, duplicates.get(), "no duplicate ids under concurrency");
        assertEquals(threads * perThread, ids.size(), "every generated id must be unique");
    }

    @Test
    void differentWorkerIdsNeverCollide() {
        SnowflakeOrderIdGenerator gen0 = generator(0);
        SnowflakeOrderIdGenerator gen1 = generator(1);
        Set<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            assertTrue(ids.add(gen0.nextId()), "worker 0 id unique");
            assertTrue(ids.add(gen1.nextId()), "worker 1 id must not collide with worker 0");
        }
    }

    @Test
    void workerIdIsEncodedInTheId() {
        long workerId = 513L;
        SnowflakeOrderIdGenerator gen = generator(workerId);
        long id = gen.nextId();
        long decodedWorker = (id >> 12) & ((1L << 10) - 1);
        assertEquals(workerId, decodedWorker, "worker id must be recoverable from the generated id");
    }

    @Test
    void rejectsWorkerIdOutOfRange() {
        SeckillProperties props = new SeckillProperties();
        props.getSnowflake().setWorkerId(1024); // > max 1023
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SnowflakeOrderIdGenerator(props, new SeckillMetrics(new SimpleMeterRegistry())));
    }

    @Test
    void clockRollbackBoundaryMatchesFailFastPolicy() {
        assertTrue(SnowflakeOrderIdGenerator.toleratesClockBackward(4));
        org.junit.jupiter.api.Assertions.assertFalse(SnowflakeOrderIdGenerator.toleratesClockBackward(5));
    }
}
