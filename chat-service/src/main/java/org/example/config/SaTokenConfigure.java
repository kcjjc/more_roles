package org.example.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截配置.
 * <p>
 * 规则: /api/** 与 /test/**(联调入口, 会触发工具调用查用户) 都必须登录,
 * 仅放行 /api/auth/**(注册/登录/注销).
 * 未登录时由拦截器抛出 {@link cn.dev33.satoken.exception.NotLoginException},
 * 再由 GlobalExceptionHandler 统一转成 Result.
 * <p>
 * ASYNC 二次分发直接放行(见拦截器内的 override): SSE 等异步请求在流结束后由容器
 * dispatch 回 DispatcherServlet 收尾, 该次分发会重走拦截器链, 但其线程上没有
 * Sa-Token 上下文(1.44 的 ThreadLocal 模式只在 REQUEST 分发类型初始化)——
 * 而 REQUEST 阶段早已完成鉴权, 收尾分发无需再校验.
 *
 * @author ckj
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /**
     * MVC 异步请求(SSE 流式对话等)的执行器: 不显式配置时 Spring MVC 用默认
     * SimpleAsyncTaskExecutor(每个请求新开线程, 无上限, 高并发下不适合生产), 启动日志会打 WARN.
     * <p>
     * 这里自建专用池而非注入 Boot 的 applicationTaskExecutor —— 它是
     * {@code @ConditionalOnMissingBean(Executor.class)}, 会被本服务 AsyncConfig 的
     * chatExecutor(摘要/标题用)顶掉而不创建, 注入会启动失败.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(64);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("mvc-async-");
        executor.initialize();
        configurer.setTaskExecutor(executor);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler ->
                // /api/** 与 /test/** 默认都要登录, 仅放行认证接口
                SaRouter.match("/api/**", "/test/**")
                        .notMatch("/api/auth/**")
                        .check(r -> StpUtil.checkLogin())
        ) {
            /** 异步请求(SSE 流式对话等)收尾的 ASYNC 分发线程上 Sa-Token 上下文不存在, 鉴权在首次 REQUEST 分发已完成 —— 直接放行 */
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;
                }
                return super.preHandle(request, response, handler);
            }
        }).addPathPatterns("/**");
    }
}
