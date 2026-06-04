package com.example.miniseckill.common;

/**
 * Order status values stored in seckill_order.status.
 */
public enum OrderStatus {
    NOT_ORDERED(0, "未下单"),
    QUEUING(1, "排队中"),
    SUCCESS(2, "成功"),
    FAILED(3, "失败"),
    CLOSED(4, "已关闭"),
    TIMEOUT(5, "排队超时");

    private final int code;
    private final String text;

    OrderStatus(int code, String text) {
        this.code = code;
        this.text = text;
    }

    public int getCode() {
        return code;
    }

    public String getText() {
        return text;
    }

    public static OrderStatus fromCode(int code) {
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return FAILED;
    }
}
