package com.example.miniseckill.config;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.service.AuthUser;
import com.example.miniseckill.service.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Optional user JWT guard for seckill user-facing endpoints.
 */
@Component
public class UserAuthInterceptor implements HandlerInterceptor {

    private final UserAuthService userAuthService;

    public UserAuthInterceptor(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!userAuthService.isUserAuthEnabled()) {
            return true;
        }
        try {
            AuthUser authUser = userAuthService.parseBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
            request.setAttribute(UserAuthService.AUTH_USER_ATTRIBUTE, authUser);
            return true;
        } catch (BusinessException ex) {
            writeUnauthorized(response, ex.getCode(), ex.getMessage());
            return false;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":" + code + ",\"message\":\"" + message + "\"}");
    }
}
