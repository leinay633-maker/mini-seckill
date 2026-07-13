package com.example.miniseckill.service;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.LoginRequest;
import com.example.miniseckill.dto.LoginResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Lightweight demo JWT service. It is not a full user center.
 */
@Service
public class UserAuthService {

    public static final String AUTH_USER_ATTRIBUTE = UserAuthService.class.getName() + ".authUser";
    private static final String TOKEN_TYPE = "Bearer";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SeckillProperties seckillProperties;
    private final ObjectMapper objectMapper;

    public UserAuthService(SeckillProperties seckillProperties, ObjectMapper objectMapper) {
        this.seckillProperties = seckillProperties;
        this.objectMapper = objectMapper;
    }

    public LoginResponse login(LoginRequest request) {
        SeckillProperties.UserAuth userAuth = seckillProperties.getUserAuth();
        if (!constantTimeEquals(request.getUsername(), userAuth.getDemoUsername())
                || !constantTimeEquals(request.getPassword(), userAuth.getDemoPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        long expiresInSeconds = userAuth.getTokenTtl().toSeconds();
        String token = createToken(userAuth.getDemoUserId(), userAuth.getDemoUsername(), expiresInSeconds);
        return new LoginResponse(
                userAuth.getDemoUserId(),
                userAuth.getDemoUsername(),
                TOKEN_TYPE,
                token,
                expiresInSeconds,
                userAuth.isEnabled()
        );
    }

    public String createToken(Long userId, String username, long expiresInSeconds) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("uid", userId);
        payload.put("username", username);
        payload.put("iat", now);
        payload.put("exp", now + expiresInSeconds);

        String signingInput = base64UrlJson(header) + "." + base64UrlJson(payload);
        return signingInput + "." + sign(signingInput);
    }

    public AuthUser parseBearerToken(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(TOKEN_TYPE + " ")) {
            throw new BusinessException(401, "用户接口未授权");
        }
        return parseToken(authorization.substring((TOKEN_TYPE + " ").length()));
    }

    public AuthUser parseToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new BusinessException(401, "用户接口未授权");
            }
            String signingInput = parts[0] + "." + parts[1];
            String expectedSignature = sign(signingInput);
            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    parts[2].getBytes(StandardCharsets.UTF_8))) {
                throw new BusinessException(401, "用户接口未授权");
            }

            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long exp = payload.path("exp").asLong(0L);
            if (exp <= Instant.now().getEpochSecond()) {
                throw new BusinessException(401, "用户登录已过期");
            }
            long userId = payload.path("uid").asLong(0L);
            String username = payload.path("username").asText("");
            if (userId <= 0 || !StringUtils.hasText(username)) {
                throw new BusinessException(401, "用户接口未授权");
            }
            return new AuthUser(userId, username);
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(401, "用户接口未授权");
        }
    }

    public void assertUserAllowed(HttpServletRequest request, Long requestedUserId) {
        if (!seckillProperties.getUserAuth().isEnabled()) {
            return;
        }
        Object authUser = request.getAttribute(AUTH_USER_ATTRIBUTE);
        if (!(authUser instanceof AuthUser user)) {
            throw new BusinessException(401, "用户接口未授权");
        }
        if (!user.getUserId().equals(requestedUserId)) {
            throw new BusinessException(403, "请求用户与登录用户不一致");
        }
    }

    public boolean isUserAuthEnabled() {
        return seckillProperties.getUserAuth().isEnabled();
    }

    private String base64UrlJson(Object value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to encode JWT JSON", ex);
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    seckillProperties.getUserAuth().getJwtSecret().getBytes(StandardCharsets.UTF_8),
                    HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    private boolean constantTimeEquals(String actual, String expected) {
        if (!StringUtils.hasText(actual) || !StringUtils.hasText(expected)) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
