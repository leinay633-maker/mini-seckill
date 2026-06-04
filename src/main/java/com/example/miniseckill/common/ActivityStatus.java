package com.example.miniseckill.common;

/**
 * Activity status values stored in seckill_activity.status.
 */
public enum ActivityStatus {
    NOT_STARTED(0, "未开始"),
    RUNNING(1, "进行中"),
    ENDED(2, "已结束"),
    CLOSED(3, "已关闭");

    private final int code;
    private final String text;

    ActivityStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public static ActivityStatus fromCode(int code) {
        for (ActivityStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return CLOSED;
    }
}
