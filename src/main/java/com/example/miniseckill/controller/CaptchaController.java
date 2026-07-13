package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.OpenApiConfig;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.CaptchaResponse;
import com.example.miniseckill.service.CaptchaService;
import com.example.miniseckill.service.UserAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Optional captcha endpoint for anti-brush demos.
 */
@Validated
@RestController
@RequestMapping("/api/captcha")
@Tag(name = "Captcha", description = "可选数学验证码")
@SecurityRequirement(name = OpenApiConfig.USER_BEARER_AUTH)
public class CaptchaController {

    private final CaptchaService captchaService;
    private final SeckillProperties seckillProperties;
    private final UserAuthService userAuthService;

    public CaptchaController(CaptchaService captchaService,
                             SeckillProperties seckillProperties,
                             UserAuthService userAuthService) {
        this.captchaService = captchaService;
        this.seckillProperties = seckillProperties;
        this.userAuthService = userAuthService;
    }

    @Operation(
            summary = "生成数学验证码",
            description = "生成绑定 activityId、userId、skuId 的一次性数学验证码。只有开启 seckill.anti-brush.captcha-enabled=true 后，获取秒杀 token 才必须携带 captchaId 和 captchaAnswer。")
    @GetMapping("/math")
    public Result<CaptchaResponse> math(@RequestParam(required = false) @Min(1) Long activityId,
                                        @RequestParam @Min(1) Long userId,
                                        @RequestParam @Min(1) Long skuId,
                                        HttpServletRequest request) {
        userAuthService.assertUserAllowed(request, userId);
        Long resolvedActivityId = activityId == null ? seckillProperties.getDefaultActivityId() : activityId;
        return Result.success(captchaService.create(resolvedActivityId, userId, skuId));
    }
}
