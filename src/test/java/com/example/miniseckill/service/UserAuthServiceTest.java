package com.example.miniseckill.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.LoginRequest;
import com.example.miniseckill.dto.LoginResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class UserAuthServiceTest {

    @Test
    void shouldLoginAndParseJwtForDemoUser() {
        UserAuthService service = service(new SeckillProperties());

        LoginResponse response = service.login(loginRequest("demo", "demo123456"));
        AuthUser authUser = service.parseBearerToken("Bearer " + response.getAccessToken());

        assertEquals(10001L, response.getUserId());
        assertEquals(10001L, authUser.getUserId());
        assertEquals("demo", authUser.getUsername());
    }

    @Test
    void shouldRejectBadDemoPassword() {
        UserAuthService service = service(new SeckillProperties());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.login(loginRequest("demo", "bad-password")));

        assertEquals(401, ex.getCode());
    }

    @Test
    void shouldSkipUserMatchWhenUserAuthDisabled() {
        SeckillProperties properties = new SeckillProperties();
        properties.getUserAuth().setEnabled(false);
        UserAuthService service = service(properties);

        assertDoesNotThrow(() -> service.assertUserAllowed(new MockHttpServletRequest(), 20001L));
    }

    @Test
    void shouldRejectMismatchedUserWhenUserAuthEnabled() {
        SeckillProperties properties = new SeckillProperties();
        properties.getUserAuth().setEnabled(true);
        UserAuthService service = service(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(UserAuthService.AUTH_USER_ATTRIBUTE, new AuthUser(10001L, "demo"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.assertUserAllowed(request, 20001L));

        assertEquals(403, ex.getCode());
    }

    private UserAuthService service(SeckillProperties properties) {
        return new UserAuthService(properties, new ObjectMapper());
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
