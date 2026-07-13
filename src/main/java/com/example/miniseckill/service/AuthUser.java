package com.example.miniseckill.service;

/**
 * Authenticated demo user carried by JWT.
 */
public class AuthUser {

    private final Long userId;
    private final String username;

    public AuthUser(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }
}
