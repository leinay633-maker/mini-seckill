package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.SeckillOrderRequest;
import com.example.miniseckill.dto.StockViewResponse;
import com.example.miniseckill.dto.TokenResponse;
import com.example.miniseckill.service.SeckillService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
public class SeckillController {

    private final SeckillService seckillService;
    private final SeckillProperties seckillProperties;

    public SeckillController(SeckillService seckillService, SeckillProperties seckillProperties) {
        this.seckillService = seckillService;
        this.seckillProperties = seckillProperties;
    }

    @PostMapping("/init")
    public Result<Void> init(@RequestParam(required = false) @Min(1) Long activityId,
                             @RequestParam @Min(1) Long skuId,
                             @RequestParam @Min(0) Integer stock) {
        return seckillService.initStock(resolveActivityId(activityId), skuId, stock);
    }

    @PostMapping("/warmup")
    public Result<Void> warmup(@RequestParam(required = false) @Min(1) Long activityId,
                               @RequestParam @Min(1) Long skuId) {
        return seckillService.warmupStock(resolveActivityId(activityId), skuId);
    }

    @PostMapping("/order")
    public Result<Void> order(@Valid @RequestBody SeckillOrderRequest request, HttpServletRequest httpRequest) {
        return seckillService.placeOrder(request, clientIp(httpRequest));
    }

    @GetMapping("/token")
    public Result<TokenResponse> token(@RequestParam(required = false) @Min(1) Long activityId,
                                       @RequestParam @Min(1) Long userId,
                                       @RequestParam @Min(1) Long skuId,
                                       HttpServletRequest httpRequest) {
        return seckillService.createOrderToken(resolveActivityId(activityId), userId, skuId, clientIp(httpRequest));
    }

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
