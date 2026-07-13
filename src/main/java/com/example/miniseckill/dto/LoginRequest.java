package com.example.miniseckill.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Demo login request for optional JWT-based user auth.
 */
public class LoginRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
