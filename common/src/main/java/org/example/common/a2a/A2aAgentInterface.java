package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A Card 的 supportedInterfaces 元素(规范 4.4.6): 声明一种协议绑定的端点与版本.
 * 本项目仅实现 HTTP+JSON/REST 绑定.
 *
 * @param url             该绑定的基址(如 http://localhost:8082)
 * @param protocolBinding 绑定类型: JSONRPC / GRPC / HTTP+JSON(自定义绑定用 URI)
 * @param protocolVersion 协议版本(Major.Minor, 如 1.0)
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aAgentInterface(String url, String protocolBinding, String protocolVersion) {
}
