package com.example.miniseckill.controller;

import com.example.miniseckill.common.Result;
import com.example.miniseckill.dto.LoginRequest;
import com.example.miniseckill.dto.LoginResponse;
import com.example.miniseckill.service.UserAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo authentication endpoint for optional JWT user auth.
 */
@Validated
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Demo JWT 登录")
public class AuthController {

    private final UserAuthService userAuthService;

    public AuthController(UserAuthService userAuthService) {
        this.userAuthService = userAuthService;
    }

    @Operation(
            summary = "Demo 用户登录",
            description = "使用内置 demo 账号换取 JWT。只有开启 seckill.user-auth.enabled=true 后，用户态接口才强制校验 Authorization: Bearer <JWT>。")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(userAuthService.login(request));
    }
}
