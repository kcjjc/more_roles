package org.example.common.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A2A Agent Card 里的一个技能: agent 能做什么的最小声明单元(规范 4.4.5).
 * 与 LLM 工具描述同构 —— 客户端(或模型)按 name/description/tags 选用 agent.
 *
 * @author ckj
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentSkill(
        String id,
        String name,
        String description,
        List<String> tags,
        List<String> examples,
        List<String> inputModes,
        List<String> outputModes) {
}
