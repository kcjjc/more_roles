package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A2A Message(规范 4.1.4): 客户端与 agent 之间的一轮通信, 含角色与 Parts.
 * {@code metadata} 是自由 KV map —— 本项目用它跨协议传业务参数(kbId),
 * 服务间已由 API key 鉴权, 不经模型。
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aMessage(
        String role,
        List<A2aTextPart> parts,
        String messageId,
        String taskId,
        String contextId,
        Map<String, Object> metadata) {

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_AGENT = "ROLE_AGENT";
}
