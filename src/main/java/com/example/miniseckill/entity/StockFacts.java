package com.example.miniseckill.entity;

/** All five fields come from ONE InnoDB consistent-read statement. */
public record StockFacts(long total, long available, long sold, long successful, long unfinished) {
    public boolean consistent() {
        return total >= 0 && available >= 0 && sold >= 0 && unfinished >= 0
                && available <= total && sold == total - available && sold == successful;
    }
    public long expected() { return Math.max(0L, available - unfinished); }
}
