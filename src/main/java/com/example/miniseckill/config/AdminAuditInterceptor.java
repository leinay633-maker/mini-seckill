package com.example.miniseckill.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Minimal audit log for admin demo endpoints.
 */
@Component
public class AdminAuditInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditInterceptor.class);
    private static final String START_TIME_ATTRIBUTE = AdminAuditInterceptor.class.getName() + ".startTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler,
                                Exception ex) {
        long elapsedMillis = elapsedMillis(request);
        log.info(
                "admin operation method={}, uri={}, status={}, elapsedMillis={}, remoteAddr={}, requestId={}, error={}",
                request.getMethod(),
                request.getRequestURI(),
                response.getStatus(),
                elapsedMillis,
                request.getRemoteAddr(),
                response.getHeader(RequestIdFilter.HEADER_NAME),
                ex == null ? "" : ex.getClass().getSimpleName()
        );
    }

    private long elapsedMillis(HttpServletRequest request) {
        Object startTime = request.getAttribute(START_TIME_ATTRIBUTE);
        if (startTime instanceof Long value) {
            return Math.max(0L, System.currentTimeMillis() - value);
        }
        return -1L;
    }
}
