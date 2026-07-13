package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.OpenApiConfig;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.dto.StockViewResponse;
import com.example.miniseckill.dto.TokenResponse;
import com.example.miniseckill.service.CaptchaService;
import com.example.miniseckill.service.SeckillService;
import com.example.miniseckill.service.UserAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP endpoints for seckill stock and order admission.
 */
@Validated
@RestController
@RequestMapping("/api/seckill")
@Tag(name = "Seckill", description = "秒杀准入、token、库存和下单")
@SecurityRequirement(name = OpenApiConfig.USER_BEARER_AUTH)
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillProperties seckillProperties;
    private final UserAuthService userAuthService;
    private final CaptchaService captchaService;

    public SeckillController(SeckillService seckillService,
                             SeckillProperties seckillProperties,
                             UserAuthService userAuthService,
                             CaptchaService captchaService) {
        this.seckillService = seckillService;
        this.seckillProperties = seckillProperties;
        this.userAuthService = userAuthService;
        this.captchaService = captchaService;
    }

    @Operation(
            summary = "初始化库存和资格池",
            description = "初始化 MySQL 库存事实、Redis 总库存、Redis 分片库存和 token 资格池。用于本地演示、压测前置准备和活动库存重置。")
    @PostMapping("/init")
    public Result<Void> init(@RequestParam(required = false) @Min(1) Long activityId,
                             @RequestParam @Min(1) Long skuId,
                             @RequestParam @Min(0) Integer stock) {
        return seckillService.initStock(resolveActivityId(activityId), skuId, stock);
    }

    @Operation(
            summary = "预热秒杀库存",
            description = "从 MySQL 当前库存重建 Redis 总库存、分片库存和 token 资格池，通常用于活动开始前预热或恢复后的手工演示。")
    @PostMapping("/warmup")
    public Result<Void> warmup(@RequestParam(required = false) @Min(1) Long activityId,
                               @RequestParam @Min(1) Long skuId) {
        return seckillService.warmupStock(resolveActivityId(activityId), skuId);
    }

    @Operation(
            summary = "兼容版下单入口",
            description = "旧版公开下单入口，保留用于已有 k6 压测脚本和本地演示。推荐新流程使用 /api/seckill/order/{orderPath}。")
    @PostMapping("/order")
    public Result<Void> order(@Valid @RequestBody SeckillOrderRequest request, HttpServletRequest httpRequest) {
        userAuthService.assertUserAllowed(httpRequest, request.getUserId());
        return seckillService.placeOrder(request, clientIp(httpRequest));
    }

    @Operation(
            summary = "隐藏 path 下单入口",
            description = "推荐下单入口。先校验获取 token 时返回的短 TTL orderPath，再进入 token 校验、限流、幂等、Redis 扣库存和 MQ 异步下单链路。")
    @PostMapping("/order/{orderPath}")
    public Result<Void> orderWithPath(@PathVariable String orderPath,
                                      @Valid @RequestBody SeckillOrderRequest request,
                                      HttpServletRequest httpRequest) {
        userAuthService.assertUserAllowed(httpRequest, request.getUserId());
        return seckillService.placeOrderWithPath(orderPath, request, clientIp(httpRequest));
    }

    @Operation(
            summary = "获取秒杀 token 和隐藏下单 path",
            description = "获取短 TTL token 与 orderPath。开启 JWT 时必须带用户 Bearer token；开启验证码时必须携带 captchaId 和 captchaAnswer。")
    @GetMapping("/token")
    public Result<TokenResponse> token(@RequestParam(required = false) @Min(1) Long activityId,
                                       @RequestParam @Min(1) Long userId,
                                       @RequestParam @Min(1) Long skuId,
                                       @RequestParam(required = false) String captchaId,
                                       @RequestParam(required = false) String captchaAnswer,
                                       HttpServletRequest httpRequest) {
        Long resolvedActivityId = resolveActivityId(activityId);
        userAuthService.assertUserAllowed(httpRequest, userId);
        captchaService.validateIfEnabled(resolvedActivityId, userId, skuId, captchaId, captchaAnswer);
        return seckillService.createOrderToken(resolvedActivityId, userId, skuId, clientIp(httpRequest));
    }

    @Operation(
            summary = "查询秒杀库存视图",
            description = "查询当前活动 SKU 的 Redis/MySQL 库存视图，主要用于本地演示、压测前后核对和恢复演练观察。")
    @GetMapping("/stock")
    public Result<StockViewResponse> stock(@RequestParam(required = false) @Min(1) Long activityId,
                                           @RequestParam @Min(1) Long skuId) {
        return Result.success(seckillService.queryStock(resolveActivityId(activityId), skuId));
    }

    private Long resolveActivityId(Long activityId) {
        return activityId == null ? seckillProperties.getDefaultActivityId() : activityId;
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
