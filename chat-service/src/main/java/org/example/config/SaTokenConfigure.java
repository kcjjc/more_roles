package org.example.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截配置.
 * <p>
 * 规则: /api/** 与 /test/**(联调入口, 会触发工具调用查用户) 都必须登录,
 * 仅放行 /api/auth/**(注册/登录/注销).
 * 未登录时由拦截器抛出 {@link cn.dev33.satoken.exception.NotLoginException},
 * 再由 GlobalExceptionHandler 统一转成 Result.
 *
 * @author ckj
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler ->
                // /api/** 与 /test/** 默认都要登录, 仅放行认证接口
                SaRouter.match("/api/**", "/test/**")
                        .notMatch("/api/auth/**")
                        .check(r -> StpUtil.checkLogin())
        )).addPathPatterns("/**");
    }
}
