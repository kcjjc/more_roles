package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * A2A message:send 的请求体(规范 3.2.1): {message, configuration?, metadata?}.
 * 本最小实现不解析 configuration(规范允许的可选项), 未知字段由 Jackson 忽略。
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aSendMessageRequest(A2aMessage message, Map<String, Object> metadata) {
}
