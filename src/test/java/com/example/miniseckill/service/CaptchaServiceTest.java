package com.example.miniseckill.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.CaptchaResponse;
import com.example.miniseckill.util.RedisKeyUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class CaptchaServiceTest {

    private static final long ACTIVITY_ID = 1L;
    private static final long USER_ID = 10001L;
    private static final long SKU_ID = 1001L;

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SeckillProperties properties;
    private CaptchaService service;

    @BeforeEach
    void setUp() {
        properties = new SeckillProperties();
        service = new CaptchaService(stringRedisTemplate, properties);
    }

    @Test
    void shouldCreateCaptchaWithRedisTtl() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        CaptchaResponse response = service.create(ACTIVITY_ID, USER_ID, SKU_ID);

        assertNotNull(response.getCaptchaId());
        assertNotNull(response.getQuestion());
        assertEquals(properties.getAntiBrush().getCaptchaTtl().toSeconds(), response.getExpiresInSeconds());
        verify(valueOperations).set(eq(RedisKeyUtil.captchaKey(response.getCaptchaId())), any(), eq(properties.getAntiBrush().getCaptchaTtl()));
    }

    @Test
    void shouldSkipValidationWhenCaptchaDisabled() {
        properties.getAntiBrush().setCaptchaEnabled(false);

        assertDoesNotThrow(() -> service.validateIfEnabled(ACTIVITY_ID, USER_ID, SKU_ID, null, null));
    }

    @Test
    void shouldValidateAndDeleteCorrectCaptchaWhenEnabled() {
        properties.getAntiBrush().setCaptchaEnabled(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyUtil.captchaKey("captcha-1"))).thenReturn("1:10001:1001:7");

        assertDoesNotThrow(() -> service.validateIfEnabled(ACTIVITY_ID, USER_ID, SKU_ID, "captcha-1", "7"));

        verify(stringRedisTemplate).delete(RedisKeyUtil.captchaKey("captcha-1"));
    }

    @Test
    void shouldRejectWrongCaptchaAnswerWhenEnabled() {
        properties.getAntiBrush().setCaptchaEnabled(true);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(RedisKeyUtil.captchaKey("captcha-1"))).thenReturn("1:10001:1001:7");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.validateIfEnabled(ACTIVITY_ID, USER_ID, SKU_ID, "captcha-1", "8"));

        assertEquals(403, ex.getCode());
    }
}
