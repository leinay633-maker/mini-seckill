package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebMvcConfigTest {

    @Test
    void adminGuardedPathsIncludeDemoOpsAndRecoveryEndpoints() {
        List<String> patterns = Arrays.asList(WebMvcConfig.adminGuardedPathPatterns());

        assertTrue(patterns.contains("/api/admin/**"));
        assertTrue(patterns.contains("/api/seckill/init"));
        assertTrue(patterns.contains("/api/seckill/warmup"));
        assertTrue(patterns.contains("/api/recovery/**"));
    }
}
