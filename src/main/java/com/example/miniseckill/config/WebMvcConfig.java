package com.example.miniseckill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String[] ADMIN_GUARDED_PATHS = {
            "/api/admin/**",
            "/api/seckill/init",
            "/api/seckill/warmup",
            "/api/recovery/**"
    };

    private final AdminAuthInterceptor adminAuthInterceptor;
    private final AdminAuditInterceptor adminAuditInterceptor;
    private final UserAuthInterceptor userAuthInterceptor;

    public WebMvcConfig(AdminAuthInterceptor adminAuthInterceptor,
                        AdminAuditInterceptor adminAuditInterceptor,
                        UserAuthInterceptor userAuthInterceptor) {
        this.adminAuthInterceptor = adminAuthInterceptor;
        this.adminAuditInterceptor = adminAuditInterceptor;
        this.userAuthInterceptor = userAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userAuthInterceptor)
                .addPathPatterns(
                        "/api/captcha/**",
                        "/api/seckill/token",
                        "/api/seckill/order",
                        "/api/seckill/order/**",
                        "/api/order/**");
        registry.addInterceptor(adminAuthInterceptor).addPathPatterns(ADMIN_GUARDED_PATHS);
        registry.addInterceptor(adminAuditInterceptor).addPathPatterns(ADMIN_GUARDED_PATHS);
    }

    static String[] adminGuardedPathPatterns() {
        return ADMIN_GUARDED_PATHS.clone();
    }
}
