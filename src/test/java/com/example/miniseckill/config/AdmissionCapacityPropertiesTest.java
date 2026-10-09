package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class AdmissionCapacityPropertiesTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import(AdmissionCapacityProperties.class)
    static class Config { }

    @Test
    void defaultsAndAblationSwitchesBindInSpring() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(context -> {
            assertNull(context.getStartupFailure());
            AdmissionCapacityProperties properties = context.getBean(AdmissionCapacityProperties.class);
            assertEquals(Duration.ofMillis(250), properties.getActivityCacheTtl());
            assertEquals(10_000, properties.getActivityCacheMaxEntries());
            assertTrue(properties.isInitialSendingEnabled());
        });
        new ApplicationContextRunner().withUserConfiguration(Config.class)
                .withPropertyValues("seckill.capacity.activity-cache-ttl=0ms",
                        "seckill.capacity.initial-sending-enabled=false",
                        "seckill.capacity.activity-cache-max-entries=2")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    AdmissionCapacityProperties properties = context.getBean(AdmissionCapacityProperties.class);
                    assertEquals(Duration.ZERO, properties.getActivityCacheTtl());
                    assertEquals(2, properties.getActivityCacheMaxEntries());
                    assertFalse(properties.isInitialSendingEnabled());
                });
    }

    @Test
    void unsafeCacheBoundsFailBindingInsteadOfSilentlyEnlargingTheStaleWindow() {
        for (String property : new String[]{"activity-cache-ttl=-1ms", "activity-cache-ttl=6s",
                "activity-cache-max-entries=0", "activity-cache-max-entries=100001"}) {
            new ApplicationContextRunner().withUserConfiguration(Config.class)
                    .withPropertyValues("seckill.capacity." + property)
                    .run(context -> assertNotNull(context.getStartupFailure(), property));
        }
    }
}
