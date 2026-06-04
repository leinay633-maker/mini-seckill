package com.example.miniseckill.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * Creates a Redisson client for Redis-based distributed locks.
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        if (redisProperties.getCluster() != null
                && !CollectionUtils.isEmpty(redisProperties.getCluster().getNodes())) {
            var clusterServerConfig = config.useClusterServers();
            redisProperties.getCluster().getNodes().stream()
                    .map(node -> node.startsWith("redis://") || node.startsWith("rediss://") ? node : "redis://" + node)
                    .forEach(clusterServerConfig::addNodeAddress);
            if (StringUtils.hasText(redisProperties.getPassword())) {
                clusterServerConfig.setPassword(redisProperties.getPassword());
            }
            if (redisProperties.getTimeout() != null) {
                clusterServerConfig.setConnectTimeout((int) redisProperties.getTimeout().toMillis());
                clusterServerConfig.setTimeout((int) redisProperties.getTimeout().toMillis());
            }
            return Redisson.create(config);
        }

        String address = "redis://" + redisProperties.getHost() + ":" + redisProperties.getPort();
        var singleServerConfig = config.useSingleServer()
                .setAddress(address)
                .setDatabase(redisProperties.getDatabase());

        if (StringUtils.hasText(redisProperties.getPassword())) {
            singleServerConfig.setPassword(redisProperties.getPassword());
        }
        if (redisProperties.getTimeout() != null) {
            singleServerConfig.setConnectTimeout((int) redisProperties.getTimeout().toMillis());
            singleServerConfig.setTimeout((int) redisProperties.getTimeout().toMillis());
        }
        return Redisson.create(config);
    }
}
