package com.example.miniseckill.common;

/**
 * Raised when MySQL stock guard rejects an order.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
