package org.example.tools;

import org.example.common.a2a.A2aSendMessageResponse;
import org.example.common.a2a.A2aTask;
import org.example.service.A2aClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * A2A 桥接工具: 把"咨询远程知识库专家 agent"暴露给模型 —— Agentic RAG 的跨协议版.
 * <p>
 * 与 {@link RagTools}(调 rag 私有 /internal 接口, MCP 风格)同一位置的可替换实现:
 * 本工具走 <b>A2A v1.0 标准协议</b>(Card 发现 + message:send + Task/Artifact 语义),
 * 远端是不透明的独立 agent(自带 LLM 生成回答 + 来源) —— 模型拿到的是"专家的答复"而非原始检索片段。
 * <p>
 * 沿用 {@link RagTools} 的全部约束: 刻意不做 {@code @Component} 单例,
 * {@link #forConversation} 按会话现造; <b>kbId 绝不作为工具参数暴露</b>(模型可控的只有 query)。
 * 降级语义: agent 不可达 / Task FAILED / 无 artifact —— 一律返回提示让模型基于角色设定自行兜底, 不中断对话。
 *
 * @author ckj
 */
public class A2aAgentTool {

    private static final String FALLBACK =
            "知识库专家 agent 暂不可用，请基于你已有的角色设定回答";

    private final A2aClient a2aClient;
    private final Long kbId;

    private A2aAgentTool(A2aClient a2aClient, Long kbId) {
        this.a2aClient = a2aClient;
        this.kbId = kbId;
    }

    /** 按会话创建工具实例: kbId 来自会话绑定(建会话时已做归属校验), 不经过模型 */
    public static A2aAgentTool forConversation(A2aClient a2aClient, Long kbId) {
        return new A2aAgentTool(a2aClient, kbId);
    }

    @Tool(description = """
            向远程的知识库专家 agent 提问（A2A 协作）。它会基于知识库文档给出带来源依据的回答；
            当用户问题涉及角色设定、背景故事、世界观或资料库文档中的事实性内容时调用；
            闲聊、问候、算术、常识问题不要调用。
            query 必须是独立完整的问句：结合对话历史补全代词指代（如"她"要写成具体角色名），
            脱离上下文也能看懂，不超过50字。
            """)
    public String searchKnowledgeBaseAgent(
            @ToolParam(description = "向专家 agent 提的完整问题，补全指代后") String query) {
        A2aSendMessageResponse resp;
        try {
            resp = a2aClient.sendMessage(query, kbId);
        } catch (Exception e) {
            return FALLBACK;
        }
        A2aTask task = resp == null ? null : resp.task();
        if (task == null || task.status() == null
                || !"TASK_STATE_COMPLETED".equals(task.status().state())) {
            return FALLBACK;
        }
        List<String> texts = task.artifacts() == null ? List.of() : task.artifacts().stream()
                .filter(a -> a != null && a.parts() != null)
                .flatMap(a -> a.parts().stream())
                .filter(p -> p != null && p.text() != null && !p.text().isBlank())
                .map(p -> p.text())
                .toList();
        if (texts.isEmpty()) {
            return FALLBACK;
        }
        return String.join("\n", texts);
    }
}
