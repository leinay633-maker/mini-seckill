package com.example.miniseckill.dto;

import java.time.Duration;

/**
 * Effective rate limit values after merging config defaults and DB overrides.
 */
public record RateLimitPlan(boolean enabled,
                            Duration window,
                            int skuLimit,
                            int userLimit,
                            int ipLimit,
                            String source) {
}
