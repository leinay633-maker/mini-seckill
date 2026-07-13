package com.example.miniseckill.config;

import com.example.miniseckill.service.SeckillMetrics;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Dedicated executor for off-path seckill_log writes (see AsyncSeckillLogWriter).
 *
 * <p>The queue is bounded and the rejection policy DROPS overflow (counted as
 * seckill_log_async_total{result=dropped}) instead of blocking the admission thread —
 * a lost audit-log row must never slow down or fail an order.
 */
@Configuration
public class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String SECKILL_LOG_EXECUTOR = "seckillLogExecutor";

    @Bean(name = SECKILL_LOG_EXECUTOR)
    public ThreadPoolTaskExecutor seckillLogExecutor(SeckillProperties seckillProperties, SeckillMetrics seckillMetrics) {
        SeckillProperties.AsyncLog config = seckillProperties.getAsyncLog();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, config.getCorePoolSize()));
        executor.setMaxPoolSize(Math.max(config.getCorePoolSize(), config.getMaxPoolSize()));
        executor.setQueueCapacity(Math.max(1, config.getQueueCapacity()));
        executor.setThreadNamePrefix("seckill-log-");
        // Drop overflow and count it; the log is a side channel, order admission must not block on it.
        executor.setRejectedExecutionHandler((r, poolExecutor) -> seckillMetrics.asyncLog("dropped"));
        // Flush the queue on shutdown so in-flight audit rows are not lost on graceful stop.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        log.info("seckillLogExecutor ready, core={}, max={}, queue={}",
                config.getCorePoolSize(), config.getMaxPoolSize(), config.getQueueCapacity());
        return executor;
    }
}
