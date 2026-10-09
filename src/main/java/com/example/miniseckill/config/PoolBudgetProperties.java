package com.example.miniseckill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Experimental partition, disabled unless explicitly selected for a retest. */
@ConfigurationProperties(prefix = "seckill.pool-budget")
public class PoolBudgetProperties {
    private boolean enabled;
    private int totalConnections = 40;
    private int consumerConnections = 28;

    public void validate() {
        if (totalConnections < 2 || totalConnections > 40) {
            throw new IllegalArgumentException("pool-budget.total-connections must be in [2,40]");
        }
        if (consumerConnections < 1 || consumerConnections >= totalConnections) {
            throw new IllegalArgumentException("consumer-connections must leave at least one admission connection");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getTotalConnections() { return totalConnections; }
    public void setTotalConnections(int value) { this.totalConnections = value; }
    public int getConsumerConnections() { return consumerConnections; }
    public void setConsumerConnections(int value) { this.consumerConnections = value; }
}
