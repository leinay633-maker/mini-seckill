package com.example.miniseckill.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.miniseckill.dto.LoginRequest;
import com.example.miniseckill.dto.LoginResponse;
import com.example.miniseckill.service.UserAuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UserAuthInterceptorTest {

    @Test
    void shouldAllowRequestWhenUserAuthDisabled() throws Exception {
        SeckillProperties properties = new SeckillProperties();
        properties.getUserAuth().setEnabled(false);
        UserAuthInterceptor interceptor = interceptor(properties);

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), null);

        assertTrue(allowed);
    }

    @Test
    void shouldRejectRequestWithoutBearerTokenWhenUserAuthEnabled() throws Exception {
        SeckillProperties properties = new SeckillProperties();
        properties.getUserAuth().setEnabled(true);
        UserAuthInterceptor interceptor = interceptor(properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, null);

        assertFalse(allowed);
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertEquals("{\"code\":401,\"message\":\"用户接口未授权\"}",
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void shouldAllowRequestWithValidBearerTokenWhenUserAuthEnabled() throws Exception {
        SeckillProperties properties = new SeckillProperties();
        properties.getUserAuth().setEnabled(true);
        UserAuthService service = service(properties);
        UserAuthInterceptor interceptor = new UserAuthInterceptor(service);
        LoginResponse loginResponse = service.login(loginRequest());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + loginResponse.getAccessToken());

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), null);

        assertTrue(allowed);
        assertNotNull(request.getAttribute(UserAuthService.AUTH_USER_ATTRIBUTE));
    }

    private UserAuthInterceptor interceptor(SeckillProperties properties) {
        return new UserAuthInterceptor(service(properties));
    }

    private UserAuthService service(SeckillProperties properties) {
        return new UserAuthService(properties, new ObjectMapper());
    }

    private LoginRequest loginRequest() {
        LoginRequest request = new LoginRequest();
        request.setUsername("demo");
        request.setPassword("demo123456");
        return request;
    }
}
