package org.example.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.example.common.Result;
import org.example.service.RagService;
import org.example.service.RagService.AskResult;
import org.example.service.RagService.KbOverviewResult;
import org.example.service.RetrievalService.ChunkHit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG 接口.
 * <p>
 * 第一步 /search: 纯检索, 验召回质量(已完成).
 * 第二步 /ask:   检索 + 拼 prompt + 调模型, 拿到基于文档的回答. 独立接口, 不碰 ChatService.
 * 受 Sa-Token 拦截(/api/** 需登录), 暂按 kbId 隔离, 未做用户级权限.
 *
 * @author ckj
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final org.example.service.RetrievalService retrievalService;
    private final RagService ragService;

    public RagController(org.example.service.RetrievalService retrievalService, RagService ragService) {
        this.retrievalService = retrievalService;
        this.ragService = ragService;
    }

    /** 检索请求体: query 必填, kbId / topK 可选 */
    public record SearchRequest(String query, Long kbId, Integer topK) {
    }

    /** 问答请求体: 同检索, query 必填, kbId / topK 可选 */
    public record AskRequest(String query, Long kbId, Integer topK) {
    }

    /** 知识库列表查询参数: kbId 可选, 传了则只返回该 id 的知识库 */
    public record KbOverviewRequest(Long kbId) {
    }

    /**
     * 检索测试: POST /api/rag/search
     * <p>body: {"query":"年假有几天","kbId":1,"topK":5} → 返回 top-K 命中分块 + 相似度分数
     */
    @PostMapping("/search")
    public Result<List<ChunkHit>> search(@RequestBody SearchRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            return Result.fail("query 不能为空");
        }
        return Result.ok(retrievalService.search(req.query(), req.kbId(), req.topK()));
    }

    /**
     * 检索增强问答: POST /api/rag/ask
     * <p>body: {"query":"年假有几天","kbId":1,"topK":5}
     * → 返回 {answer: 模型基于文档的回答, sources: 命中的来源片段}
     */
    @PostMapping("/ask")
    public Result<AskResult> ask(@RequestBody AskRequest req) {
        if (req == null || req.query() == null || req.query().isBlank()) {
            return Result.fail("query 不能为空");
        }
        return Result.ok(ragService.ask(req.query(), req.kbId(), req.topK()));
    }

    /**
     * 列出【当前登录用户】的知识库: GET /api/rag/list
     * <p>kbId 可选: 传了就按 id 精确筛选, 不传则返回该用户的全部知识库.
     * 分页参数 page 从 0 开始, size 默认 10.
     */
    @GetMapping("/list")
    public Result<Page<KbOverviewResult>> list(KbOverviewRequest req,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size) {
        Long userId = StpUtil.getLoginIdAsLong();
        Pageable pageable = PageRequest.of(page, size);
        return Result.ok(ragService.listKbs(userId, req.kbId(), pageable));
    }
}
