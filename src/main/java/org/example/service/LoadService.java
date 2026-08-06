package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.Document;
import org.example.entity.IndexTask;
import org.example.repository.DocumentRepository;
import org.example.repository.IndexTaskRepository;
import org.example.service.loader.ParseResult;
import org.example.service.splitter.ChunkResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    public LoadService(DocumentRepository documentRepository,IndexTaskRepository indexTaskRepository,ChunkService chunkService) {
        this.documentRepository = documentRepository;
        this.indexTaskRepository = indexTaskRepository;
        this.chunkService = chunkService;

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
