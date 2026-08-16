package org.example.config;

import cn.dev33.satoken.context.SaHolder;
import cn.dev33.satoken.reactor.filter.SaReactorFilter;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 网关鉴权配置(响应式).
 * <p>
 * 规则与原单体的 SaTokenConfigure 一致: /api/** 与 /test/** 必须登录, 放行 /api/auth/**(注册/登录).
 * 会话在 Redis(三个服务共享), 登录动作在 chat-service 完成, 网关只做校验.
 * 未登录返回统一 Result JSON(与业务服务的 GlobalExceptionHandler 输出同构).
 *
 * @author ckj
 */
@Configuration
public class SaTokenConfigure {

    @Bean
    public SaReactorFilter saReactorFilter() {
        return new SaReactorFilter()
                // 拦截所有经过网关的请求(路由表中没有的路径本来就不会被转发)
                .addInclude("/**")
                .setAuth(obj -> SaRouter.match("/api/**", "/test/**")
                        .notMatch("/api/auth/**")
                        .check(r -> StpUtil.checkLogin()))
                // 未登录/异常 → 统一 JSON 信封, 与业务服务的返回结构保持一致
                .setError(e -> {
                    String message = e instanceof cn.dev33.satoken.exception.NotLoginException
                            ? "未登录或登录已过期" : e.getMessage();
                    SaHolder.getResponse()
                            .setStatus(200)
                            .setHeader("Content-Type", "application/json;charset=UTF-8");
                    return "{\"code\":500,\"message\":\"" + message + "\",\"data\":null}";
                });
    }
}
