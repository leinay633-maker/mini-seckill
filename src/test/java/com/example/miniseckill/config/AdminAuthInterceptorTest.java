package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminAuthInterceptorTest {

    @Test
    void shouldAllowAdminRequestWhenGuardDisabled() throws Exception {
        SeckillProperties properties = new SeckillProperties();
        properties.getAdminAuth().setEnabled(false);
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(properties);

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null);

        assertTrue(allowed);
    }

    @Test
    void shouldRejectAdminRequestWithoutTokenWhenGuardEnabled() throws Exception {
        AdminAuthInterceptor interceptor = interceptorWithToken("secret-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, null);

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals("{\"code\":401,\"message\":\"管理员接口未授权\"}",
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldAllowAdminRequestWithConfiguredHeaderToken() throws Exception {
        AdminAuthInterceptor interceptor = interceptorWithToken("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Admin-Token", "secret-token");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), null);

        assertTrue(allowed);
    }

    @Test
    void shouldAllowAdminRequestWithBearerToken() throws Exception {
        AdminAuthInterceptor interceptor = interceptorWithToken("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer secret-token");

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), null);

        assertTrue(allowed);
    }

    @Test
    void shouldFailClosedWhenEnabledWithoutConfiguredToken() throws Exception {
        SeckillProperties properties = new SeckillProperties();
        properties.getAdminAuth().setEnabled(true);
        AdminAuthInterceptor interceptor = new AdminAuthInterceptor(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, null);

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
    }

    private AdminAuthInterceptor interceptorWithToken(String token) {
        SeckillProperties properties = new SeckillProperties();
        properties.getAdminAuth().setEnabled(true);
        properties.getAdminAuth().setToken(token);
        return new AdminAuthInterceptor(properties);
    }
}
