package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.Document;
import org.example.entity.KnowledgeBase;
import org.example.repository.DocChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 知识库文档上传服务: 校验 → MinIO 分块上传 → 建 Document 记录 → 提交异步索引.
 * <p>
 * 上传请求只负责"文件落 MinIO + 建档 + 投递任务", 解析/分块/向量化在
 * indexTaskExecutor 线程池异步进行(见 LoadService), 接口立即返回.
 *
 * @author ckj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    /** 上传的文件类型白名单(与 DocumentLoaderService 已装配的解析器一致) */
    private static final Set<String> SUPPORTED_TYPES = Set.of(
            Document.FILE_TYPE_PDF, Document.FILE_TYPE_DOCX,
            Document.FILE_TYPE_MD, Document.FILE_TYPE_TXT);

    /** 单文件大小上限; 与 application.yaml 的 spring.servlet.multipart.max-file-size 保持一致 */
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final DocChunkRepository docChunkRepository;
    private final DocumentLoaderService documentLoaderService;
    private final MinioStorageService minioStorageService;
    private final IndexService indexService;
    private final TransactionTemplate transactionTemplate;

    /**
     * 往知识库添加一个文件: 校验归属与文件 → 上传 MinIO → 建档(PENDING) → 提交异步索引.
     * <p>立即返回, 索引进度看 document.status / index_task.
     */
    public UploadResult addFile(Long kbId, Long userId, MultipartFile file) {
        // 1. 知识库必须存在、未删除、且属于当前用户
        knowledgeBaseRepository.findByIdAndCreatedByAndDeletedFalse(kbId, userId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: kbId=" + kbId));

        // 2. 文件校验: 非空 / 类型在白名单 / 不超大小上限
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String fileType = documentLoaderService.detectFileType(fileName);
        if (!SUPPORTED_TYPES.contains(fileType)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileType + ", 目前支持 PDF / DOCX / MD / TXT");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件超过大小上限 50MB: " + fileName);
        }

        // 3. MinIO 对象路径: {kbId}/{uuid}.{ext} —— 防重名防乱码, 原始文件名存 document 表
        String objectPath = kbId + "/" + UUID.randomUUID()
                + fileName.substring(fileName.lastIndexOf('.'));

        // 4. 分块上传(超 5MB 自动 multipart; bucket 不存在自动建)
        try (InputStream in = file.getInputStream()) {
            minioStorageService.upload(objectPath, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败: " + fileName, e);
        }

        // 5. 建档(PENDING)后提交异步索引
        Document document = new Document();
        document.setKbId(kbId);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFileSize(file.getSize());
        document.setMinioPath(objectPath);
        document.setUploadedBy(userId);
        Document saved = documentRepository.save(document);
        indexService.submitTaskFromMinio(saved.getId());

        log.info("[文档上传] kbId={}, docId={}, fileName={}, {}字节, 已提交异步索引",
                kbId, saved.getId(), fileName, file.getSize());
        return new UploadResult(saved.getId(), fileName, fileType, saved.getStatus());
    }

    /**
     * 列出知识库内的全部文档(未删除, 按上传时间倒序).
     * <p>含索引状态/失败原因/分块数等 —— 上传后前端轮询 {@code status} 从 PENDING 变 DONE/FAILED 用.
     */
    public List<DocumentOverview> listFiles(Long kbId, Long userId) {
        knowledgeBaseRepository.findByIdAndCreatedByAndDeletedFalse(kbId, userId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: kbId=" + kbId));
        return documentRepository.findByKbIdAndDeletedFalseOrderByUploadedAtDesc(kbId).stream()
                .map(d -> new DocumentOverview(
                        d.getId(), d.getFileName(), d.getFileType(), d.getFileSize(),
                        d.getStatus(), d.getErrorMsg(), d.getChunkCount(), d.getTokenCount(),
                        d.getVersion(), d.getUploadedAt(), d.getIndexedAt()))
                .toList();
    }

    /**
     * 删除知识库内的一个文档: 校验归属与状态 → 短事务(物理删分块 + 软删文档) → 提交后清理 MinIO 对象.
     * <p>
     * 三个刻意的删除语义:
     * <ol>
     *   <li><b>分块物理删、文档软删</b>: 检索的原生 SQL 只查 doc_chunk 按 kb_id 过滤、不 join document,
     *       分块不删则检索仍命中 —— 这是"删除后立即检索不到"的唯一保障; 文档记录软删保留可追溯
     *       (查询侧全部按 DeletedFalse 过滤, 列表/轮询自动消失)。</li>
     *   <li><b>PENDING / PROCESSING 拒绝</b>: 索引任务还在异步跑, 此时删除分块会被任务写回。
     *       这层只是 UX 拦截(省一次白跑的 embedding 调用), 竞态的兜底在 LoadService 的"已删除即放弃"守卫。</li>
     *   <li><b>MinIO 删除失败仅告警不回滚</b>: 反过来先删对象再落库, 失败会出现"源文件没了但检索仍命中"
     *       的中间态; 而库已删、对象残留只是浪费存储空间。removeObject 幂等, 假 minioPath 也不会报错。</li>
     * </ol>
     */
    public DeleteResult deleteFile(Long kbId, Long docId, Long userId) {
        // 1. 知识库必须存在、未删除、且属于当前用户(与 addFile 同款校验)
        knowledgeBaseRepository.findByIdAndCreatedByAndDeletedFalse(kbId, userId)
                .orElseThrow(() -> new IllegalArgumentException("知识库不存在: kbId=" + kbId));

        // 2. 文档必须存在、未删除、且属于该库(按 kbId 过滤 → 删别的库的 docId 也走"不存在")
        Document document = documentRepository.findByIdAndKbIdAndDeletedFalse(docId, kbId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: docId=" + docId));

        // 3. 索引中的文档拒绝删除(重复删除则被上一步的 DeletedFalse 挡住, 报"不存在")
        if (Document.STATUS_PENDING.equals(document.getStatus())
                || Document.STATUS_PROCESSING.equals(document.getStatus())) {
            throw new IllegalArgumentException("文档正在索引中, 请稍后再试");
        }

        // 4. 短事务: 物理删分块 + 软删文档(编程式事务, executeWithoutResult 返回即已提交;
        //    不用 @Transactional 注解 —— 否则第 5 步的 MinIO 网络调用会被圈进事务占用 DB 连接)
        transactionTemplate.executeWithoutResult(tx -> {
            docChunkRepository.deleteByDocId(docId);
            document.setDeleted(true);
            documentRepository.save(document);
        });

        // 5. 事务提交后清理 MinIO 对象, 失败只告警(见方法注释第 3 点)
        try {
            minioStorageService.delete(document.getMinioPath());
        } catch (Exception e) {
            log.warn("[文档删除] MinIO 对象清理失败, 仅残留存储空间, 不影响删除结果, docId={}, objectPath={}",
                    docId, document.getMinioPath(), e);
        }

        log.info("[文档删除] kbId={}, docId={}, fileName={}, 分块已清理, 文档已软删",
                kbId, docId, document.getFileName());
        return new DeleteResult(docId, document.getFileName());
    }

    /** 上传结果: 文档 id + 初始状态(索引异步进行, 完成后变为 DONE) */
    public record UploadResult(Long docId, String fileName, String fileType, String status) {
    }

    /** 删除结果: 被删文档 id + 文件名(前端删除确认回显用) */
    public record DeleteResult(Long docId, String fileName) {
    }

    /** 知识库内文档的概览: 元信息 + 索引状态(列表/轮询用) */
    public record DocumentOverview(Long docId, String fileName, String fileType, Long fileSize,
                                   String status, String errorMsg, Integer chunkCount,
                                   Integer tokenCount, Integer version,
                                   LocalDateTime uploadedAt, LocalDateTime indexedAt) {
    }
}
