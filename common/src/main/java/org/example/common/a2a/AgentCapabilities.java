package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A2A agent 的可选能力声明(规范 4.4.3): 客户端使用未声明的能力时
 * 服务端必须返回 UnsupportedOperationError —— 本实现仅声明已实现的, 其余省略.
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCapabilities(
        Boolean streaming,
        Boolean pushNotifications,
        Boolean extendedAgentCard) {
}
