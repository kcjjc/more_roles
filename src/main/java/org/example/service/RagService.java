package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.service.RetrievalService.ChunkHit;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 问答服务: 检索 → 拼 prompt → 调模型.
 * <p>
 * 这是 RAG 的"第二刀": 独立的检索增强问答, 【不】接会话记忆/人格(那是第三刀接 ChatService 时做).
 * 目的是先把"检索 + 模型"这条最小链路验通, 出了问题能立刻定位是检索还是模型.
 * <p>
 * 两个关键设计:
 *  1. system 里带"防幻觉"约束 —— 模型即使资料里没有也会用自身知识编, 必须用规则压住;
 *  2. 检索到的片段不只喂模型, 也随 answer 一起返回(sources), 让调用方看到"依据", 便于核验.
 *
 * @author ckj
 */
@Service
@Slf4j
public class RagService {

    private final RetrievalService retrievalService;
    /** 中立 ChatClient: 不带 defaultSystem(不带人格), system 由本服务按 RAG 场景定制注入 */
    private final ChatClient chatClient;

    /** 防幻觉约束: 必须! 否则模型会用资料外的知识"热心编造"看似合理的答案 */
    private static final String SYSTEM = """
            你是一个严谨的知识库问答助手。请【只】根据下面提供的【参考资料】回答用户问题。
            规则:
            1. 答案必须能在参考资料里找到依据, 禁止使用资料外的知识或自行推测。
            2. 如果参考资料里没有相关内容, 直接回答: "根据现有资料无法回答该问题。"
            3. 回答简洁、直接, 可适当引用资料原文。
            """;

    public RagService(RetrievalService retrievalService, ChatClient.Builder chatClientBuilder) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClientBuilder.build();   // 构建一次复用, 和 ChatService 同款写法
    }

    /**
     * 检索增强问答: 检索 → 拼 prompt → 调模型.
     *
     * @param query 用户问题
     * @param kbId  知识库 id; null = 全局检索
     * @param topK  召回条数; null = 用默认值
     * @return 模型回答 + 命中的来源片段
     */
    public AskResult ask(String query, Long kbId, Integer topK) {
        // 1. 检索(复用第一刀的 RetrievalService)
        List<ChunkHit> hits = retrievalService.search(query, kbId, topK);
        if (hits.isEmpty()) {
            return new AskResult("未检索到任何相关文档, 无法回答。", List.of());
        }

        // 2. 把检索片段拼成"参考资料" + 用户问题, 组成 user message
        String userMessage = buildUserMessage(hits, query);

        // 3. 调模型: system = 防幻觉约束, user = 资料 + 问题
        String answer;
        try {
            answer = chatClient.prompt()
                    .system(SYSTEM)
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[RAG] 调模型失败, query={}", query, e);
            throw new IllegalStateException("模型调用失败: " + e.getMessage(), e);
        }
        if (answer == null || answer.isBlank()) {
            answer = "模型返回为空。";
        }
        log.info("[RAG] 问答完成, query=\"{}\", 命中{}段, 回答{}字",
                query, hits.size(), answer.length());
        return new AskResult(answer, hits);
    }

    /** 拼接 user message: 【参考资料】片段列表 + 【用户问题】 */
    private String buildUserMessage(List<ChunkHit> hits, String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("【参考资料】\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append("[").append(i + 1).append("] ")
              .append(hits.get(i).content())
              .append("\n\n");
        }
        sb.append("【用户问题】\n").append(query);
        return sb.toString();
    }

    /** 问答结果: 模型回答 + 命中来源(让前端/调用方能核验依据) */
    public record AskResult(String answer, List<ChunkHit> sources) {
    }
}
