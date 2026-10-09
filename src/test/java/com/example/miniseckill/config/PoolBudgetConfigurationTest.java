package com.example.miniseckill.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

class PoolBudgetConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withUserConfiguration(PoolBudgetConfiguration.class)
            .withPropertyValues("spring.datasource.url=jdbc:mysql://localhost/not_opened",
                    "spring.datasource.username=test", "spring.datasource.password=test",
                    "spring.datasource.hikari.maximum-pool-size=40", "spring.datasource.hikari.minimum-idle=8",
                    "spring.datasource.hikari.connection-timeout=2000");

    @Test void disabledLeavesTheOriginalSingleFortyConnectionPool() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(DataSource.class);
            HikariDataSource pool = ctx.getBean(HikariDataSource.class);
            assertEquals(40, pool.getMaximumPoolSize());
            assertEquals(8, pool.getMinimumIdle());
        });
    }
    @Test void enabledCreatesExactlyTwoPhysicalPoolsWithUnchangedTotalBudget() {
        context.withPropertyValues("seckill.pool-budget.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertEquals(2, ctx.getBeansOfType(HikariDataSource.class).size());
            assertEquals(3, ctx.getBeansOfType(DataSource.class).size());
            HikariDataSource admission = ctx.getBean("admissionDataSource", HikariDataSource.class);
            HikariDataSource consumer = ctx.getBean("consumerDataSource", HikariDataSource.class);
            assertEquals(12, admission.getMaximumPoolSize());
            assertEquals(28, consumer.getMaximumPoolSize());
            assertEquals(8, admission.getMinimumIdle() + consumer.getMinimumIdle());
            assertEquals(2000, consumer.getConnectionTimeout());
            assertEquals("consumer", consumer.getPoolName());
            assertEquals("admission", admission.getPoolName());
            assertSame(ctx.getBean("dataSource"), ctx.getBean(DataSource.class));
        });
    }
    @ParameterizedTest @ValueSource(ints = {0, 40, 41, -1})
    void invalidPartitionFailsAtStartup(int reserved) {
        context.withPropertyValues("seckill.pool-budget.enabled=true", "seckill.pool-budget.consumer-connections=" + reserved)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void mismatchedGlobalLimitFailsInsteadOfSilentlyDoublingCapacity() {
        context.withPropertyValues("seckill.pool-budget.enabled=true", "spring.datasource.hikari.maximum-pool-size=80")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void oversizedTotalFailsEvenWhenHikariMatches() {
        context.withPropertyValues("seckill.pool-budget.enabled=true", "seckill.pool-budget.total-connections=80",
                "spring.datasource.hikari.maximum-pool-size=80").run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void idleBudgetCannotExceedTotal() {
        context.withPropertyValues("seckill.pool-budget.enabled=true", "spring.datasource.hikari.minimum-idle=41")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
    @Test void alternativeSplitIsConfigurableWithoutChangingForty() {
        context.withPropertyValues("seckill.pool-budget.enabled=true", "seckill.pool-budget.consumer-connections=20")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertEquals(20, ctx.getBean("admissionDataSource", HikariDataSource.class).getMaximumPoolSize());
                    assertEquals(20, ctx.getBean("consumerDataSource", HikariDataSource.class).getMaximumPoolSize());
                });
    }
    @Test void routerUsesOnlyTheListenerRoleAndRestoresItOnExit() throws Exception {
        HikariDataSource admission = mock(HikariDataSource.class);
        HikariDataSource consumer = mock(HikariDataSource.class);
        Connection first = mock(Connection.class), second = mock(Connection.class);
        when(admission.getConnection()).thenReturn(first);
        when(consumer.getConnection()).thenReturn(second);
        DataSource router = new PoolBudgetConfiguration(new PoolBudgetProperties(), new DataSourceProperties(),
                new MockEnvironment()).dataSource(admission, consumer);
        assertSame(first, router.getConnection());
        try (ConsumerPoolContext.Scope ignored = ConsumerPoolContext.enter()) {
            assertSame(second, router.getConnection());
            try (ConsumerPoolContext.Scope nested = ConsumerPoolContext.enter()) {
                assertTrue(ConsumerPoolContext.isConsumer());
            }
            assertTrue(ConsumerPoolContext.isConsumer());
        }
        assertSame(first, router.getConnection());
        assertFalse(ConsumerPoolContext.isConsumer());
    }
    @Test void roleDoesNotLeakAcrossThreadsOrExceptionalExit() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            assertThrows(IllegalStateException.class, () -> {
                try (ConsumerPoolContext.Scope ignored = ConsumerPoolContext.enter()) {
                    assertFalse(executor.submit(ConsumerPoolContext::isConsumer).get(5, TimeUnit.SECONDS));
                    throw new IllegalStateException("injected");
                }
            });
            assertFalse(ConsumerPoolContext.isConsumer());
        } finally { executor.shutdownNow(); }
    }
    @Test void retryBackoffIsBoundedAndZeroIsAvailableForTests() {
        ConsumerExecutionProperties execution = new ConsumerExecutionProperties();
        assertEquals(Duration.ofMillis(250), execution.getRetryBackoff());
        execution.setRetryBackoff(Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> execution.setRetryBackoff(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> execution.setRetryBackoff(Duration.ofSeconds(31)));
        assertThrows(IllegalArgumentException.class, () -> execution.setRetryBackoff(null));
    }
}
