package com.example.miniseckill.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Optional token guard for admin-only demo endpoints.
 */
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final SeckillProperties seckillProperties;

    public AdminAuthInterceptor(SeckillProperties seckillProperties) {
        this.seckillProperties = seckillProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        SeckillProperties.AdminAuth adminAuth = seckillProperties.getAdminAuth();
        if (!adminAuth.isEnabled()) {
            return true;
        }
        if (hasValidToken(request, adminAuth)) {
            return true;
        }
        writeUnauthorized(response);
        return false;
    }

    private boolean hasValidToken(HttpServletRequest request, SeckillProperties.AdminAuth adminAuth) {
        String expectedToken = adminAuth.getToken();
        if (!StringUtils.hasText(expectedToken)) {
            return false;
        }
        if (StringUtils.hasText(adminAuth.getHeaderName())) {
            String headerToken = request.getHeader(adminAuth.getHeaderName());
            if (constantTimeEquals(headerToken, expectedToken)) {
                return true;
            }
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return constantTimeEquals(authorization.substring("Bearer ".length()), expectedToken);
    }

    private boolean constantTimeEquals(String actualToken, String expectedToken) {
        if (!StringUtils.hasText(actualToken)) {
            return false;
        }
        byte[] actual = actualToken.getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(actual, expected);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":401,\"message\":\"管理员接口未授权\"}");
    }
}
