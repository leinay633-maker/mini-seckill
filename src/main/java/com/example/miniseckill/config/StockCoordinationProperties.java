package com.example.miniseckill.config;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Correctness policy, not a throughput-tuned configuration. All nodes must agree. */
@Component
@ConfigurationProperties(prefix = "seckill.coordination")
public class StockCoordinationProperties {
    private Duration repairLease = Duration.ofSeconds(20);
    private Duration reservationTimeout = Duration.ofSeconds(60);
    private int recoveryBatchSize = 100;
    private boolean faultsEnabled;
    private String faultDirectory = "";
    private Duration faultWait = Duration.ofMinutes(3);
    @PostConstruct
    public void validate() {
        if (repairLease == null || repairLease.toMillis() < 100
                || reservationTimeout == null || reservationTimeout.toMillis() < 100
                || recoveryBatchSize < 1 || recoveryBatchSize > 1000
                || faultWait == null || faultWait.isNegative() || faultWait.isZero()) {
            throw new IllegalArgumentException("invalid stock coordination policy");
        }
    }
    public Duration getRepairLease() { return repairLease; }
    public void setRepairLease(Duration v) { repairLease = v; }
    public Duration getReservationTimeout() { return reservationTimeout; }
    public void setReservationTimeout(Duration v) { reservationTimeout = v; }
    public int getRecoveryBatchSize() { return recoveryBatchSize; }
    public void setRecoveryBatchSize(int v) { recoveryBatchSize = v; }
    public boolean isFaultsEnabled() { return faultsEnabled; }
    public void setFaultsEnabled(boolean v) { faultsEnabled = v; }
    public String getFaultDirectory() { return faultDirectory; }
    public void setFaultDirectory(String v) { faultDirectory = v; }
    public Duration getFaultWait() { return faultWait; }
    public void setFaultWait(Duration v) { faultWait = v; }
}
