package org.example.service;

import com.openhtmltopdf.render.displaylist.PagedBoxCollector;
import lombok.extern.slf4j.Slf4j;
import org.example.repository.KnowledgeBaseRepository;
import org.example.service.RetrievalService.ChunkHit;
import org.example.entity.KnowledgeBase;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    /** 防幻觉约束: 必须! 否则模型会用资料外的知识"热心编造"看似合理的答案 */
    private static final String SYSTEM = """
            你是一个严谨的知识库问答助手。请【只】根据下面提供的【参考资料】回答用户问题。
            规则:
            1. 答案必须能在参考资料里找到依据, 禁止使用资料外的知识或自行推测。
            2. 如果参考资料里没有相关内容, 直接回答: "根据现有资料无法回答该问题。"
            3. 回答简洁、直接, 可适当引用资料原文。
            """;

    public RagService(RetrievalService retrievalService, ChatClient.Builder chatClientBuilder, KnowledgeBaseRepository knowledgeBaseRepository) {
        this.retrievalService = retrievalService;
        this.chatClient = chatClientBuilder.build();   // 构建一次复用, 和 ChatService 同款写法
        this.knowledgeBaseRepository = knowledgeBaseRepository;
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

    /**
     * 列出某用户的知识库概览(分页).
     * <p>
     * kbId 非空时, 在该用户的知识库范围内按 id 精确筛选; 为 null 则返回该用户的全部知识库.
     */
    public Page<KbOverviewResult> listKbs(Long userId, Long kbId, Pageable pageable) {
        Page<KnowledgeBase> kbs = (kbId == null)
                ? knowledgeBaseRepository.findByCreatedByAndDeletedFalse(userId, pageable)
                : knowledgeBaseRepository.findByCreatedByAndIdAndDeletedFalse(userId, kbId, pageable);
        return kbs.map(kb -> new KbOverviewResult(kb.getId(), kb.getName(), kb.getDescription()));
    }


    /**
     * 新建知识库.
     * <p>同一用户下【未删除】的知识库不允许重名(软删除的名字可重新使用);
     * 名称列宽 100, 超长由调用方(Controller)先校验.
     */
    public KbOverviewResult createKb(Long userId, String name, String description) {
        if (!knowledgeBaseRepository.findByCreatedByAndNameAndDeletedFalse(userId, name).isEmpty()) {
            throw new IllegalArgumentException("知识库名称已存在: " + name);
        }
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(name);
        kb.setDescription(description);
        kb.setCreatedBy(userId);
        KnowledgeBase saved = knowledgeBaseRepository.save(kb);
        log.info("[RAG] 新建知识库: kbId={}, name={}, userId={}", saved.getId(), name, userId);
        return new KbOverviewResult(saved.getId(), saved.getName(), saved.getDescription());
    }

    /**
     * kbId 非空时校验其存在且属于当前用户(未删除), 不通过抛 IllegalArgumentException(全局 handler 转 Result).
     * search / ask 等检索入口调用, 防止跨用户读别人库里的文档.
     */
    public void requireOwnedKb(Long userId, Long kbId) {
        if (kbId == null) {
            return;  // null = 全局检索, 保持现状
        }
        knowledgeBaseRepository.findByIdAndCreatedByAndDeletedFalse(kbId, userId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: kbId=" + kbId));
    }


    /** 问答结果: 模型回答 + 命中来源(让前端/调用方能核验依据) */
    public record AskResult(String answer, List<ChunkHit> sources) {
    }

    public record KbOverviewResult(Long id,String name,String description) {
    }
}
