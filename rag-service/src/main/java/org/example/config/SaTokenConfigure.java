package org.example.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截配置(rag-service).
 * <p>
 * 规则: /api/rag/** 必须登录; /internal/**(服务间内部接口) 不拦截 ——
 * 调用方是容器网络内的可信服务(chat-service), 且网关不路由 /internal/**.
 * 会话存 Redis(与 chat-service 共享), 登录动作在 chat-service 完成.
 * 未登录时由拦截器抛 {@link cn.dev33.satoken.exception.NotLoginException},
 * 再由 GlobalExceptionHandler 统一转成 Result.
 *
 * @author ckj
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler ->
                SaRouter.match("/api/**")
                        .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");
    }
}
