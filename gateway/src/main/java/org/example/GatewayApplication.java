package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gateway 启动类: 统一入口(8080), 登录校验后按路径转发到 chat-service / rag-service.
 * 前端只见 8080, 两个业务服务不对外暴露.
 *
 * @author ckj
 */
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
