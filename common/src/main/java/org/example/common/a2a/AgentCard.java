package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * A2A Agent Card(协议 v1.0, HTTP+JSON 绑定): agent 的"名片", 发布在
 * {@code /.well-known/agent-card.json}, 供客户端发现身份/能力/技能/端点/认证方式.
 * <p>
 * 字段对齐官方规范 4.4.1; camelCase 序列化; 空字段省略(规范的正则化要求)。
 *
 * @param name                agent 名称(必填)
 * @param description         能力描述
 * @param supportedInterfaces 支持的协议接口(有序, 首个为首选)
 * @param version             card 版本
 * @param capabilities        可选能力声明(streaming/pushNotifications/...)
 * @param securitySchemes     认证方案声明(OpenAPI 风格)
 * @param security            生效的认证方案组合(如 [{"apiKey": []}])
 * @param defaultInputModes   默认输入媒体类型
 * @param defaultOutputModes  默认输出媒体类型
 * @param skills              技能列表(调用方按 skill 语义选择 agent)
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCard(
        String name,
        String description,
        List<A2aAgentInterface> supportedInterfaces,
        String version,
        AgentCapabilities capabilities,
        Map<String, A2aSecurityScheme> securitySchemes,
        List<Map<String, List<String>>> security,
        List<String> defaultInputModes,
        List<String> defaultOutputModes,
        List<AgentSkill> skills) {
}
