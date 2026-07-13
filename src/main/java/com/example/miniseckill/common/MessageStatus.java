package com.example.miniseckill.common;

/**
 * Local message table status values.
 */
public enum MessageStatus {
    PENDING(0),
    SENT(1),
    CONSUMED(2),
    FAILED(3),
    CONFIRM_FAILED(4),
    RETURNED(5),
    TIMEOUT(6),
    DEAD(7),
    REPLAYED(8),
    SENDING(9),
    CONSUMING(10);

    private final int code;

    MessageStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public boolean isFinalStatus() {
        return this == CONSUMED || this == TIMEOUT || this == DEAD;
    }
}
