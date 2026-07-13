package com.example.miniseckill.service.impl;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.service.OrderIdGenerator;
import com.example.miniseckill.service.SeckillMetrics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Snowflake order id generator: 1 sign bit + 41 timestamp bits + 10 worker bits + 12 sequence bits.
 *
 * <p>Each application instance must own a distinct workerId so ids never collide across the
 * multi-instance deployment (see {@code docker-compose.app-scale.yml}). The old helper used
 * {@code currentTimeMillis() * 1000 + AtomicInteger}, which produced identical ids on two
 * instances in the same millisecond and let a real order be swallowed as a duplicate.
 */
@Component
public class SnowflakeOrderIdGenerator implements OrderIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SnowflakeOrderIdGenerator.class);

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;      // 1023
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;       // 4095
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                 // 12
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS; // 22

    /** Clock drift below this is tolerated by spin-waiting; at or above we refuse to mint an id. */
    private static final long MAX_BACKWARD_MILLIS = 5L;

    private final long workerId;
    private final long epoch;
    private final SeckillMetrics seckillMetrics;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeOrderIdGenerator(SeckillProperties seckillProperties, SeckillMetrics seckillMetrics) {
        this.seckillMetrics = seckillMetrics;
        SeckillProperties.Snowflake config = seckillProperties.getSnowflake();
        long resolved = config.getWorkerId();
        if (resolved < 0 || resolved > MAX_WORKER_ID) {
            throw new IllegalArgumentException("snowflake worker-id must be within [0, " + MAX_WORKER_ID + "], but was " + resolved);
        }
        this.workerId = resolved;
        this.epoch = config.getEpochMillis();
    }

    @PostConstruct
    void logConfig() {
        if (workerId == 0L) {
            // 0 is a valid id, but it is also the fallback when MINI_SECKILL_WORKER_ID is unset.
            // In a multi-instance deployment two workers both defaulting to 0 would mint colliding
            // ids and let a real order be swallowed as a duplicate — the exact bug this replaced.
            log.warn("SnowflakeOrderIdGenerator using workerId=0 (default). In multi-instance deployments, "
                    + "set a distinct MINI_SECKILL_WORKER_ID per instance to avoid colliding order ids.");
        } else {
            log.info("SnowflakeOrderIdGenerator ready, workerId={}, epoch={}", workerId, epoch);
        }
    }

    @Override
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (toleratesClockBackward(offset)) {
                timestamp = waitUntil(lastTimestamp);
            } else {
                seckillMetrics.orderId("clock_backward");
                throw new IllegalStateException("clock moved backwards by " + offset + "ms, refusing to generate order id");
            }
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // Sequence exhausted within this millisecond; advance to the next one.
                timestamp = waitUntil(lastTimestamp + 1);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;
        return ((timestamp - epoch) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    static boolean toleratesClockBackward(long offsetMillis) {
        return offsetMillis < MAX_BACKWARD_MILLIS;
    }

    private long waitUntil(long target) {
        long timestamp = System.currentTimeMillis();
        while (timestamp < target) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
