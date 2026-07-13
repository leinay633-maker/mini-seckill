package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuditInterceptorTest {

    private final AdminAuditInterceptor interceptor = new AdminAuditInterceptor();

    @Test
    void shouldAllowAndCompleteAdminAuditLog() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/rate-limit");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        response.setHeader(RequestIdFilter.HEADER_NAME, "req-001");

        assertTrue(interceptor.preHandle(request, response, null));

        interceptor.afterCompletion(request, response, null, null);
    }
}
