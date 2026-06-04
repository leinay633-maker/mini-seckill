package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.config.SeckillProperties;
import com.example.miniseckill.dto.OrderQueryResponse;
import com.example.miniseckill.service.OrderService;
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
public class OrderController {

    private final OrderService orderService;
    private final SeckillProperties seckillProperties;

    public OrderController(OrderService orderService, SeckillProperties seckillProperties) {
        this.orderService = orderService;
        this.seckillProperties = seckillProperties;
    }

    @GetMapping("/query")
    public Result<OrderQueryResponse> query(@RequestParam(required = false) @Min(1) Long activityId,
                                            @RequestParam @Min(1) Long userId,
                                            @RequestParam @Min(1) Long skuId) {
        Long resolvedActivityId = activityId == null ? seckillProperties.getDefaultActivityId() : activityId;
        return Result.success(orderService.queryOrder(resolvedActivityId, userId, skuId));
    }
}
