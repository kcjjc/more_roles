package org.example.controller;

import org.example.common.Result;
import org.example.common.rag.RetrievalHit;
import org.example.common.rag.RetrievalRequest;
import org.example.repository.KnowledgeBaseRepository;
import org.example.service.RetrievalService;
import org.example.service.RetrievalService.ChunkHit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间内部接口: 仅供 chat-service 在容器网络内调用.
 * <p>
 * 与面向前端的 /api/rag/** 的区别: 无登录拦截(SaTokenConfigure 只拦 /api/**),
 * 网关也不路由 /internal/** —— 外部流量到不了这里.
 *
 * @author ckj
 */
@RestController
@RequestMapping("/internal")
public class InternalRetrievalController {

    private final RetrievalService retrievalService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public InternalRetrievalController(RetrievalService retrievalService,
                                       KnowledgeBaseRepository knowledgeBaseRepository) {
        this.retrievalService = retrievalService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 检索: POST /internal/retrieval
     * chat 侧把路由改写后的检索句发过来, 向量化 + 相似度过滤 + 阈值过滤都在本服务完成.
     */
    @PostMapping("/retrieval")
    public Result<List<RetrievalHit>> retrieve(@RequestBody RetrievalRequest req) {
        List<ChunkHit> hits = retrievalService.search(req.query(), req.kbId(), req.topK());
        List<RetrievalHit> out = hits.stream()
                .map(h -> new RetrievalHit(h.id(), h.docId(), h.kbId(), h.chunkIndex(),
                        h.content(), h.pageNum(), h.sectionTitle(), h.score()))
                .toList();
        return Result.ok(out);
    }

    /**
     * 知识库归属校验: GET /internal/kb/{kbId}/owned?userId=xxx
     * chat 新建绑库会话前确认 kb 存在且属于该用户. 不存在与不属于返回值相同(true 才放行),
     * 与单体时代"查不到即报知识库不存在"的语义一致, 不泄露他人库的存在性.
     */
    @GetMapping("/kb/{kbId}/owned")
    public Result<Boolean> kbOwned(@PathVariable Long kbId, @RequestParam Long userId) {
        boolean owned = knowledgeBaseRepository
                .findByIdAndCreatedByAndDeletedFalse(kbId, userId).isPresent();
        return Result.ok(owned);
    }
}
