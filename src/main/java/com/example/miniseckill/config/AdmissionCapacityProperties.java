package com.example.miniseckill.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Independent ablation switches. Neither setting changes the MySQL connection budget. */
@Component
@ConfigurationProperties(prefix = "seckill.capacity")
public class AdmissionCapacityProperties {

    private Duration activityCacheTtl = Duration.ofMillis(250);
    private int activityCacheMaxEntries = 10_000;
    private boolean initialSendingEnabled = true;

    public Duration getActivityCacheTtl() {
        return activityCacheTtl;
    }

    public void setActivityCacheTtl(Duration activityCacheTtl) {
        if (activityCacheTtl == null || activityCacheTtl.isNegative()
                || activityCacheTtl.compareTo(Duration.ofSeconds(5)) > 0) {
            throw new IllegalArgumentException("activity-cache-ttl must be between 0 and 5s (0 disables caching)");
        }
        this.activityCacheTtl = activityCacheTtl;
    }

    public int getActivityCacheMaxEntries() {
        return activityCacheMaxEntries;
    }

    public void setActivityCacheMaxEntries(int activityCacheMaxEntries) {
        if (activityCacheMaxEntries < 1 || activityCacheMaxEntries > 100_000) {
            throw new IllegalArgumentException("activity-cache-max-entries must be between 1 and 100000");
        }
        this.activityCacheMaxEntries = activityCacheMaxEntries;
    }

    public boolean isInitialSendingEnabled() {
        return initialSendingEnabled;
    }

    public void setInitialSendingEnabled(boolean initialSendingEnabled) {
        this.initialSendingEnabled = initialSendingEnabled;
    }
}
