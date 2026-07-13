package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.OpenApiConfig;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.OrderQueryResponse;
import com.example.miniseckill.service.OrderService;
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
 * HTTP endpoint for querying async order result.
 */
@Validated
@RestController
@RequestMapping("/api/order")
@Tag(name = "Order", description = "异步订单结果查询")
@SecurityRequirement(name = OpenApiConfig.USER_BEARER_AUTH)
public class OrderController {

    private final OrderService orderService;
    private final SeckillProperties seckillProperties;
    private final UserAuthService userAuthService;

    public OrderController(OrderService orderService,
                           SeckillProperties seckillProperties,
                           UserAuthService userAuthService) {
        this.orderService = orderService;
        this.seckillProperties = seckillProperties;
        this.userAuthService = userAuthService;
    }

    @Operation(
            summary = "查询异步下单结果",
            description = "按 activityId、userId、skuId 查询订单结果。订单未落库时会结合 Redis 排队状态返回排队中，适合下单后轮询。")
    @GetMapping("/query")
    public Result<OrderQueryResponse> query(@RequestParam(required = false) @Min(1) Long activityId,
                                            @RequestParam @Min(1) Long userId,
                                            @RequestParam @Min(1) Long skuId,
                                            HttpServletRequest request) {
        userAuthService.assertUserAllowed(request, userId);
        Long resolvedActivityId = activityId == null ? seckillProperties.getDefaultActivityId() : activityId;
        return Result.success(orderService.queryOrder(resolvedActivityId, userId, skuId));
    }
}
