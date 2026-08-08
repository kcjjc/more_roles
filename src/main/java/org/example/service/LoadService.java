package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.DocChunk;
import org.example.entity.Document;
import org.example.entity.IndexTask;
import org.example.repository.DocChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.IndexTaskRepository;
import org.example.service.loader.ParseResult;
import org.example.service.splitter.ChunkResult;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author ckj
 */
@Service
@Slf4j
public class LoadService {
    private final DocumentRepository documentRepository;
    private final IndexTaskRepository indexTaskRepository;
    private final ChunkService chunkService;
    private final EmbeddingService embeddingService;
    private final DocChunkRepository docChunkRepository;
    private final MinioStorageService minioStorageService;
    private final DocumentLoaderService loaderService;

    public LoadService(
            DocumentRepository documentRepository,
            IndexTaskRepository indexTaskRepository,
            ChunkService chunkService,
            EmbeddingService embeddingService,
            MinioStorageService minioStorageService,
            DocChunkRepository docChunkRepository,
            DocumentLoaderService documentLoaderService) {
        this.documentRepository = documentRepository;
        this.indexTaskRepository = indexTaskRepository;
        this.chunkService = chunkService;
        this.embeddingService = embeddingService;
        this.minioStorageService = minioStorageService;
        this.docChunkRepository = docChunkRepository;
        this.loaderService = documentLoaderService;

    }
    public void executeWithText(Long taskId, Long docId, String textContent) {
        // 先找出文档
        Document document = documentRepository.findById(docId).orElseThrow();
        // 构造解析结果 就那么点先自己来
        ParseResult parseResult = ParseResult.builder()
                .success(true)
                .pages(List.of(ParseResult.PageContent.builder()
                        .pageNum(1)
                        .text(textContent)
                        .build()))
                .totalPages(1)
                .build();
        // 调用索引方法
            doIndex(taskId,docId,document,parseResult);
    }

    private void doIndex(Long taskId, Long docId, Document document, ParseResult parseResult) {
        try {
            // 1. 先更新任务表
            updateTaskStatus(taskId, IndexTask.STATUS_PROCESSING);
            // 2. 更新一下文档表
            updateDocumentStatus(docId, Document.STATUS_PROCESSING);
            // 3. 然后进行索引
            if (!parseResult.isSuccess()) {
                throw new RuntimeException("文档" + document.getFileName() + "解析失败");
            }
            // 4. 分块
            List<ChunkResult> chunks = chunkService.chunk(parseResult);
            if (chunks.isEmpty()) {
                throw new RuntimeException("分块结果为空，文档可能无有效文本内容");
            }
            log.info("[IndexService] docId={}，分块完成，共{}块", docId, chunks.size());
            // 第二步：批量 Embedding
            List<String> texts = chunks.stream().map(ChunkResult::getContent).toList();
            List<float[]> embeddings = embeddingService.embedBatch(texts);

            // 第三步：删除旧版本分块（放在 Embedding 成功之后，保证有新数据才删旧数据）
            docChunkRepository.deleteByDocIdAndDocVersionNot(docId, document.getVersion());

            // 第四步：批量写入数据库
            List<DocChunk> docChunks = new ArrayList<>();
            int totalTokens = 0;
            for (int i = 0; i < chunks.size(); i++) {
                ChunkResult chunk = chunks.get(i);
                DocChunk docChunk = new DocChunk();
                docChunk.setDocId(docId);
                docChunk.setKbId(document.getKbId());
                docChunk.setChunkIndex(chunk.getChunkIndex());
                docChunk.setContent(chunk.getContent());
                docChunk.setEmbedding(embeddings.get(i));
                docChunk.setPageNum(chunk.getPageNum());
                docChunk.setSectionTitle(chunk.getSectionTitle());
                docChunk.setTokenCount(chunk.getEstimatedTokens());
                docChunk.setDocVersion(document.getVersion());
                docChunks.add(docChunk);
                totalTokens += chunk.getEstimatedTokens();
            }

            batchInsertChunks(docChunks);

            // 第五步：更新文档状态
            document.setStatus(Document.STATUS_DONE);
            document.setChunkCount(chunks.size());
            document.setTokenCount(totalTokens);
            document.setIndexedAt(LocalDateTime.now());
            documentRepository.save(document);

            updateTaskStatus(taskId, IndexTask.STATUS_DONE);

            log.info("[IndexService] 索引完成：docId={}，chunks={}，tokens={}",
                    docId, chunks.size(), totalTokens);
        } catch (Exception e) {
            log.error("[IndexService] 索引失败：docId={}，error={}", docId, e.getMessage(), e);
            markFailed(taskId, docId, e.getMessage());
            retryIfPossible(taskId, docId);
        }


    }

    private void retryIfPossible(Long taskId, Long docId) {
        IndexTask task = indexTaskRepository.findById(taskId).orElseThrow();
        if (task.canRetry()) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(IndexTask.STATUS_PENDING);
            indexTaskRepository.save(task);
            log.info("[IndexService] 任务将重试：taskId={}，retryCount={}", taskId, task.getRetryCount());
            // 延迟重试（指数退避：1s, 2s, 4s）
            scheduleRetry(taskId, docId, task.getRetryCount());
        }
    }

    private void scheduleRetry(Long taskId, Long docId, Integer retryCount) {
        try {
            long delay = (long) Math.pow(2, retryCount - 1) * 1000;
            Thread.sleep(delay);
            executeFromMinio(taskId, docId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void executeFromMinio(Long taskId, Long docId) {
        Document doc = documentRepository.findById(docId).orElseThrow();
        try {
            byte[] fileBytes = minioStorageService.download(doc.getMinioPath());
            ParseResult parseResult = loaderService.load(
                    new ByteArrayInputStream(fileBytes), doc.getFileName());
            doIndex(taskId, docId, doc, parseResult);
        } catch (Exception e) {
            markFailed(taskId, docId, "从MinIO读取文件失败：" + e.getMessage());
        }
    }

    private void markFailed(Long taskId, Long docId, String message) {
        IndexTask task = indexTaskRepository.findById(taskId).orElseThrow();
        task.setStatus(IndexTask.STATUS_FAILED);
        task.setErrorMsg(message);
        task.setFinishedAt(LocalDateTime.now());
        indexTaskRepository.save(task);

        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus(Document.STATUS_FAILED);
            doc.setErrorMsg(message);
            documentRepository.save(doc);
        });
    }

    private void batchInsertChunks(List<DocChunk> docChunks) {
        int batchSize = 50;
        for (int i = 0; i < docChunks.size(); i += batchSize) {
            List<DocChunk> batch = docChunks.subList(i, Math.min(i + batchSize, docChunks.size()));
            docChunkRepository.saveAll(batch);
            log.debug("[IndexService] 写入批次 {}/{}",
                    i / batchSize + 1, (docChunks.size() + batchSize - 1) / batchSize);
        }
    }

    private void updateDocumentStatus(Long docId, String status) {
        documentRepository.findById(docId).ifPresent(doc -> {
            doc.setStatus(status);
            documentRepository.save(doc);
        });
    }

    private void updateTaskStatus(Long taskId, String status) {
        indexTaskRepository.findById(taskId).ifPresent(task -> {
            task.setStatus(status);
            // 然后更新时间
            if (IndexTask.STATUS_PROCESSING.equals(status)) task.setStartedAt(LocalDateTime.now());
            if (IndexTask.STATUS_DONE.equals(status)) task.setFinishedAt(LocalDateTime.now());
            indexTaskRepository.save(task);
        });
    }
}
