package com.example.miniseckill.dto;

/**
 * Math captcha challenge for optional anti-brush flow.
 */
public class CaptchaResponse {

    private String captchaId;
    private String question;
    private Long expiresInSeconds;

    public CaptchaResponse() {
    }

    public CaptchaResponse(String captchaId, String question, Long expiresInSeconds) {
        this.captchaId = captchaId;
        this.question = question;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getCaptchaId() {
        return captchaId;
    }

    public void setCaptchaId(String captchaId) {
        this.captchaId = captchaId;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Long getExpiresInSeconds() {
        return expiresInSeconds;
    }

    public void setExpiresInSeconds(Long expiresInSeconds) {
        this.expiresInSeconds = expiresInSeconds;
    }
}
