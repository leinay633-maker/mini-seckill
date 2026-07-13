package com.example.miniseckill.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.RateLimitPlan;
import com.example.miniseckill.entity.RateLimitRule;
import com.example.miniseckill.mapper.RateLimitRuleMapper;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DynamicRateLimitServiceImplTest {

    @Mock
    private RateLimitRuleMapper rateLimitRuleMapper;

    @Test
    void effectivePlanUsesApplicationFallbackWhenNoDynamicRuleExists() {
        SeckillProperties properties = properties();
        when(rateLimitRuleMapper.selectByActivitySku(1L, 1001L)).thenReturn(null);

        DynamicRateLimitServiceImpl service = new DynamicRateLimitServiceImpl(rateLimitRuleMapper, properties);
        RateLimitPlan plan = service.effectivePlan(1L, 1001L);
        RateLimitPlan cachedPlan = service.effectivePlan(1L, 1001L);

        assertTrue(plan.enabled());
        assertEquals(Duration.ofSeconds(2), plan.window());
        assertEquals(300, plan.skuLimit());
        assertEquals(5, plan.userLimit());
        assertEquals(80, plan.ipLimit());
        assertEquals("application.yml", plan.source());
        assertEquals(plan, cachedPlan);
        verify(rateLimitRuleMapper).selectByActivitySku(1L, 1001L);
    }

    @Test
    void effectivePlanMergesDynamicRuleWithApplicationFallbackAndCachesResult() {
        SeckillProperties properties = properties();
        RateLimitRule rule = new RateLimitRule();
        rule.setEnabled(1);
        rule.setWindowSeconds(3);
        rule.setSkuLimit(120);
        rule.setIpLimit(40);
        when(rateLimitRuleMapper.selectByActivitySku(1L, 1001L)).thenReturn(rule);

        DynamicRateLimitServiceImpl service = new DynamicRateLimitServiceImpl(rateLimitRuleMapper, properties);
        RateLimitPlan first = service.effectivePlan(1L, 1001L);
        RateLimitPlan second = service.effectivePlan(1L, 1001L);

        assertTrue(first.enabled());
        assertEquals(Duration.ofSeconds(3), first.window());
        assertEquals(120, first.skuLimit());
        assertEquals(5, first.userLimit());
        assertEquals(40, first.ipLimit());
        assertEquals("db-rule", first.source());
        assertEquals(first, second);
        verify(rateLimitRuleMapper).selectByActivitySku(1L, 1001L);
    }

    @Test
    void effectivePlanCanBeDisabledByDynamicRule() {
        SeckillProperties properties = properties();
        RateLimitRule rule = new RateLimitRule();
        rule.setEnabled(0);
        rule.setWindowSeconds(0);
        when(rateLimitRuleMapper.selectByActivitySku(1L, 1001L)).thenReturn(rule);

        RateLimitPlan plan = new DynamicRateLimitServiceImpl(rateLimitRuleMapper, properties)
                .effectivePlan(1L, 1001L);

        assertFalse(plan.enabled());
        assertEquals(Duration.ofSeconds(1), plan.window());
        assertEquals(0, plan.skuLimit());
        assertEquals(0, plan.userLimit());
        assertEquals(0, plan.ipLimit());
        assertEquals("db-rule", plan.source());
    }

    private SeckillProperties properties() {
        SeckillProperties properties = new SeckillProperties();
        properties.getRateLimit().setEnabled(true);
        properties.getRateLimit().setWindow(Duration.ofSeconds(2));
        properties.getRateLimit().setSkuLimit(300);
        properties.getRateLimit().setUserLimit(5);
        properties.getRateLimit().setIpLimit(80);
        properties.getDynamicRateLimit().setEnabled(true);
        properties.getDynamicRateLimit().setCacheTtl(Duration.ofMinutes(1));
        return properties;
    }
}
