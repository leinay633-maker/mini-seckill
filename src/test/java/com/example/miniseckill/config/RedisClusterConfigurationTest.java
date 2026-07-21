package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class RedisClusterConfigurationTest {

    @Test
    void mqConsumerStartsByDefault() {
        assertTrue(new SeckillProperties().getMqConsumer().isAutoStartup());
    }

    @Test
    void redisClusterProfileDisablesCrossSlotSingleLuaMode() throws IOException {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(
                "redis-cluster",
                new ClassPathResource("application-redis-cluster.yml")
        );
        MutablePropertySources propertySources = new MutablePropertySources();
        loaded.forEach(propertySources::addLast);

        SeckillProperties properties = new Binder(ConfigurationPropertySources.from(propertySources))
                .bind("seckill", Bindable.of(SeckillProperties.class))
                .orElseThrow(() -> new IllegalStateException("seckill properties are missing"));

        assertFalse(properties.getStockShard().isSingleLuaEnabled());
    }
}
