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
import org.springframework.web.multipart.MultipartFile;

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
    private final org.example.service.DocumentService documentService;

    public RagController(org.example.service.RetrievalService retrievalService, RagService ragService,
                         org.example.service.DocumentService documentService) {
        this.retrievalService = retrievalService;
        this.ragService = ragService;
        this.documentService = documentService;
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

    /** 新建知识库请求体: name 必填(≤100字), description 可选 */
    public record CreateKbRequest(String name, String description) {
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
        ragService.requireOwnedKb(StpUtil.getLoginIdAsLong(), req.kbId());
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
        ragService.requireOwnedKb(StpUtil.getLoginIdAsLong(), req.kbId());
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

    /**
     * 新建知识库: POST /api/rag/kb
     * <p>body: {"name":"公司制度库","description":"可选"} → 返回新建的知识库概览.
     * 同一用户下未删除的知识库不允许重名.
     */
    @PostMapping("/kb")
    public Result<KbOverviewResult> createKb(@RequestBody CreateKbRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            return Result.fail("name 不能为空");
        }
        if (req.name().length() > 100) {
            return Result.fail("name 长度不能超过 100");
        }
        return Result.ok(ragService.createKb(StpUtil.getLoginIdAsLong(), req.name().trim(), req.description()));
    }

    /**
     * 往知识库添加文件: POST /api/rag/kb/{kbId}/document
     * <p>multipart/form-data, 字段名 file; 支持 PDF / DOCX / MD / TXT, 单文件 ≤ 50MB.
     * 上传成功立即返回(PENDING), 解析/分块/向量化异步进行.
     */
    @PostMapping("/kb/{kbId}/document")
    public Result<org.example.service.DocumentService.UploadResult> uploadDocument(
            @PathVariable Long kbId, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.fail("file 不能为空");
        }
        return Result.ok(documentService.addFile(kbId, StpUtil.getLoginIdAsLong(), file));
    }

    /**
     * 列出知识库内的文件: GET /api/rag/kb/{kbId}/document
     * <p>与上传同路径(GET 列表 / POST 上传). 返回文件名/类型/大小/索引状态(含失败原因)/分块数等,
     * 上传后前端轮询 status 从 PENDING → DONE/FAILED 用.
     */
    @GetMapping("/kb/{kbId}/document")
    public Result<List<org.example.service.DocumentService.DocumentOverview>> listDocuments(
            @PathVariable Long kbId) {
        return Result.ok(documentService.listFiles(kbId, StpUtil.getLoginIdAsLong()));
    }
}
