package com.example.miniseckill.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves no oversell under real concurrency against a real Redis, turning the "zero oversell" claim
 * from a load-test observation into a reproducible CI assertion.
 *
 * <p>Runs under the integration-test profile (Failsafe, {@code *IT.java}); skipped when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class SeckillConcurrencyIT {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private DefaultRedisScript<Long> stockScript;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        stockScript = new DefaultRedisScript<>();
        stockScript.setLocation(new ClassPathResource("lua/seckill_stock.lua"));
        stockScript.setResultType(Long.class);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void luaStockDeductionNeverOversellsUnderConcurrency() throws InterruptedException {
        String key = "seckill:stock:1:1001";
        int stock = 100;
        int threads = 200;
        redis.opsForValue().set(key, String.valueOf(stock));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    Long r = redis.execute(stockScript, Collections.singletonList(key));
                    if (Long.valueOf(1L).equals(r)) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all threads should finish");
        pool.shutdownNow();

        assertEquals(stock, success.get(), "exactly `stock` requests may succeed — no oversell, no undersell");
        assertEquals("0", redis.opsForValue().get(key), "stock key must land exactly at 0");
    }

    @Test
    void shardedSingleLuaNeverOversellsUnderConcurrency() throws InterruptedException {
        // Covers the default production path (A3 seckill_stock_sharded.lua), not just the bare single-key script.
        DefaultRedisScript<Long> shardedScript = new DefaultRedisScript<>();
        shardedScript.setLocation(new ClassPathResource("lua/seckill_stock_sharded.lua"));
        shardedScript.setResultType(Long.class);

        int buckets = 8;
        int perBucket = 20;
        int total = buckets * perBucket; // 160
        int threads = 400;
        java.util.List<String> bucketKeys = new java.util.ArrayList<>();
        for (int b = 0; b < buckets; b++) {
            String bk = "seckill:stock:1:1001:bucket:" + b;
            bucketKeys.add(bk);
            redis.opsForValue().set(bk, String.valueOf(perBucket));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            int startIdx = Math.floorMod(i, buckets);
            pool.submit(() -> {
                try {
                    start.await();
                    Long r = redis.execute(shardedScript, bucketKeys, String.valueOf(startIdx));
                    if (r != null && r >= 0L) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "all threads should finish");
        pool.shutdownNow();

        int remaining = 0;
        for (String bk : bucketKeys) {
            remaining += Integer.parseInt(redis.opsForValue().get(bk));
        }
        assertEquals(total, success.get(), "exactly total stock may succeed across shards — no oversell");
        assertEquals(0, remaining, "every shard must land exactly at 0");
    }
}
