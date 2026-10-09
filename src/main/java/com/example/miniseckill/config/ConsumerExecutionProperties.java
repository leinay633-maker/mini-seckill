package com.example.miniseckill.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "seckill.consumer-execution")
public class ConsumerExecutionProperties {
    private Duration retryBackoff = Duration.ofMillis(250);

    public Duration getRetryBackoff() { return retryBackoff; }

    public void setRetryBackoff(Duration value) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("consumer retry-backoff must be between 0 and 30s");
        }
        retryBackoff = value;
    }
}
