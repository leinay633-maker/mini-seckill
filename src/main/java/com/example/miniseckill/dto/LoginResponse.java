package com.example.miniseckill.dto;

/**
 * JWT login response for demo user auth.
 */
public class LoginResponse {

    private Long userId;
    private String username;
    private String tokenType;
    private String accessToken;
    private Long expiresInSeconds;
    private Boolean authEnabled;

    public LoginResponse() {
    }

    public LoginResponse(Long userId,
                         String username,
                         String tokenType,
                         String accessToken,
                         Long expiresInSeconds,
                         Boolean authEnabled) {
        this.userId = userId;
        this.username = username;
        this.tokenType = tokenType;
        this.accessToken = accessToken;
        this.expiresInSeconds = expiresInSeconds;
        this.authEnabled = authEnabled;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(Long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }

    public Boolean getAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(Boolean authEnabled) {
        this.authEnabled = authEnabled;
    }
}
