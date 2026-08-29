package org.example.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * A2A 操作端点的 API key 校验.
 * <p>
 * A2A 端点走的是<b>跨信任边界</b>的标准协议(任何 A2A 客户端都能发现并调用),
 * 不能沿用 /internal 的"仅容器网络可达"裸奔假设 —— 由 Agent Card 声明、
 * 请求头 {@code X-Api-Key} 携带(与 Card 的 securitySchemes 声明一致)。
 * Card 发现端点公开(规范如此, Card 只含公开信息)。
 * <p>
 * Sa-Token 拦截器只拦 /api/** 与 /test/**, A2A 路径不在其列, 两套认证互不干扰。
 *
 * @author ckj
 */
@Component
@Order(1)
public class A2aAuthFilter extends OncePerRequestFilter {

    private final String apiKey;

    public A2aAuthFilter(@Value("${a2a.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // 只拦 A2A 操作端点; /.well-known/** 是公开发现入口
        String path = request.getRequestURI();
        boolean a2aOperation = path.equals("/message:send") || path.startsWith("/tasks/");
        if (a2aOperation) {
            if (apiKey == null || apiKey.isBlank()) {
                // 服务端未配置 key = 部署失误, 直接拒绝而非裸奔
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "A2A api key not configured");
                return;
            }
            String provided = request.getHeader("X-Api-Key");
            if (!apiKey.equals(provided)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing X-Api-Key");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
