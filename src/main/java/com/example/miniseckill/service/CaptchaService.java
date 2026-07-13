package com.example.miniseckill.service;

import com.example.miniseckill.common.BusinessException;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.CaptchaResponse;
import com.example.miniseckill.util.RedisKeyUtil;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Optional math captcha guard before issuing seckill tokens.
 */
@Service
public class CaptchaService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProperties seckillProperties;

    public CaptchaService(StringRedisTemplate stringRedisTemplate, SeckillProperties seckillProperties) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.seckillProperties = seckillProperties;
    }

    public CaptchaResponse create(Long activityId, Long userId, Long skuId) {
        int left = ThreadLocalRandom.current().nextInt(1, 10);
        int right = ThreadLocalRandom.current().nextInt(1, 10);
        String captchaId = UUID.randomUUID().toString().replace("-", "");
        String value = value(activityId, userId, skuId, left + right);
        stringRedisTemplate.opsForValue().set(
                RedisKeyUtil.captchaKey(captchaId),
                value,
                seckillProperties.getAntiBrush().getCaptchaTtl());
        return new CaptchaResponse(
                captchaId,
                left + " + " + right + " = ?",
                seckillProperties.getAntiBrush().getCaptchaTtl().toSeconds());
    }

    public void validateIfEnabled(Long activityId,
                                  Long userId,
                                  Long skuId,
                                  String captchaId,
                                  String captchaAnswer) {
        if (!seckillProperties.getAntiBrush().isCaptchaEnabled()) {
            return;
        }
        if (!StringUtils.hasText(captchaId) || !StringUtils.hasText(captchaAnswer)) {
            throw new BusinessException(403, "缺少验证码");
        }
        String key = RedisKeyUtil.captchaKey(captchaId);
        String expected = stringRedisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(expected)) {
            throw new BusinessException(403, "验证码无效或已过期");
        }
        stringRedisTemplate.delete(key);
        String actual = value(activityId, userId, skuId, captchaAnswer.trim());
        if (!expected.equals(actual)) {
            throw new BusinessException(403, "验证码错误");
        }
    }

    private String value(Long activityId, Long userId, Long skuId, Object answer) {
        return activityId + ":" + userId + ":" + skuId + ":" + answer;
    }
}
