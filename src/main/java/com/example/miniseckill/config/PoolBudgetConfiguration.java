package com.example.miniseckill.config;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/** Two physical pools, one transaction-manager/MyBatis DataSource identity.
 * A connection already bound to a transaction is never rerouted mid-transaction. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "seckill.pool-budget", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({PoolBudgetProperties.class, DataSourceProperties.class})
public class PoolBudgetConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PoolBudgetConfiguration.class);
    private final PoolBudgetProperties budget;
    private final DataSourceProperties source;
    private final Environment environment;
    private final int totalIdle;

    public PoolBudgetConfiguration(PoolBudgetProperties budget, DataSourceProperties source,
                                   Environment environment) {
        budget.validate();
        Integer configuredMaximum = environment.getProperty(
                "spring.datasource.hikari.maximum-pool-size", Integer.class);
        if (configuredMaximum != null && configuredMaximum != budget.getTotalConnections()) {
            throw new IllegalArgumentException("Hikari maximum-pool-size must equal pool-budget.total-connections");
        }
        this.totalIdle = environment.getProperty("spring.datasource.hikari.minimum-idle", Integer.class,
                Math.min(8, budget.getTotalConnections()));
        if (totalIdle < 0 || totalIdle > budget.getTotalConnections()) {
            throw new IllegalArgumentException("Hikari minimum-idle must fit the total connection budget");
        }
        this.budget = budget;
        this.source = source;
        this.environment = environment;
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource admissionDataSource() {
        int maximum = budget.getTotalConnections() - budget.getConsumerConnections();
        int idle = totalIdle * maximum / budget.getTotalConnections();
        return pool("admission", maximum, idle);
    }

    @Bean(destroyMethod = "close")
    public HikariDataSource consumerDataSource() {
        int admissionMaximum = budget.getTotalConnections() - budget.getConsumerConnections();
        int admissionIdle = totalIdle * admissionMaximum / budget.getTotalConnections();
        return pool("consumer", budget.getConsumerConnections(), totalIdle - admissionIdle);
    }

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("admissionDataSource") HikariDataSource admission,
                                 @Qualifier("consumerDataSource") HikariDataSource consumer) {
        AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
            @Override
            protected Object determineCurrentLookupKey() {
                return ConsumerPoolContext.isConsumer() ? "consumer" : "admission";
            }
        };
        routing.setTargetDataSources(Map.of("admission", admission, "consumer", consumer));
        routing.setDefaultTargetDataSource(admission);
        routing.setLenientFallback(false);
        routing.afterPropertiesSet();
        log.info("pool budget enabled: admission={}, consumer={}, total={}, totalMinimumIdle={}",
                admission.getMaximumPoolSize(), consumer.getMaximumPoolSize(),
                budget.getTotalConnections(), totalIdle);
        return routing;
    }

    private HikariDataSource pool(String name, int maximum, int idle) {
        // Bind the existing URL, credentials and Hikari tuning before enforcing
        // the partition. No third/shared physical pool is created when enabled.
        HikariDataSource pool = source.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(pool));
        pool.setMaximumPoolSize(maximum);
        pool.setMinimumIdle(idle);
        pool.setPoolName(name);
        return pool;
    }
}
