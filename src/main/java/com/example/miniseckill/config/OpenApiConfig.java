package com.example.miniseckill.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI metadata for interview demo and manual smoke tests.
 */
@Configuration
public class OpenApiConfig {

    public static final String USER_BEARER_AUTH = "userBearerAuth";
    public static final String ADMIN_TOKEN_AUTH = "adminTokenAuth";

    @Bean
    public OpenAPI miniSeckillOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MiniSeckill API")
                        .version("0.0.1")
                        .description("Java 17 + Spring Boot 秒杀核心链路演示接口"))
                .components(new Components()
                        .addSecuritySchemes(USER_BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("用户态接口可选 JWT；默认关闭，开启 seckill.user-auth.enabled 后生效"))
                        .addSecuritySchemes(ADMIN_TOKEN_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Admin-Token")
                                .description("管理接口共享 token；默认关闭，开启 seckill.admin-auth.enabled 后生效")));
    }
}
