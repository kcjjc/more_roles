package org.example.service;

import org.example.entity.Document;
import org.example.entity.IndexTask;
import org.example.repository.DocChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.IndexTaskRepository;
import org.example.service.loader.ParseResult;
import org.example.service.splitter.ChunkResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LoadService "已删除守卫"的纯单测(全部依赖 mock):
 * 覆盖 任务入口拦截(已删文档不下载不索引) / 写分块前重查放弃(防分块写回与过期实体 merge 复活).
 *
 * @author ckj
 */
class LoadServiceTest {

    private static final Long TASK_ID = 11L;
    private static final Long DOC_ID = 7L;

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final IndexTaskRepository indexTaskRepository = mock(IndexTaskRepository.class);
    private final ChunkService chunkService = mock(ChunkService.class);
    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final DocChunkRepository docChunkRepository = mock(DocChunkRepository.class);
    private final MinioStorageService minioStorageService = mock(MinioStorageService.class);
    private final DocumentLoaderService loaderService = mock(DocumentLoaderService.class);
    private final LoadService loadService = new LoadService(
            documentRepository, indexTaskRepository, chunkService, embeddingService,
            minioStorageService, docChunkRepository, loaderService);

    private static Document doc(String status, boolean deleted) {
        Document d = new Document();
        d.setId(DOC_ID);
        d.setKbId(1L);
        d.setFileName("员工手册.pdf");
        d.setFileType(Document.FILE_TYPE_PDF);
        d.setFileSize(2048L);
        d.setMinioPath("1/uuid.pdf");
        d.setStatus(status);
        d.setVersion(1);
        d.setDeleted(deleted);
        return d;
    }

    private void givenTask() {
        IndexTask task = new IndexTask();
        task.setId(TASK_ID);
        task.setDocId(DOC_ID);
        when(indexTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
    }

    @Test
    void executeFromMinio_文档已软删_取消任务且不下载不索引() {
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(doc(Document.STATUS_FAILED, true)));
        givenTask();

        loadService.executeFromMinio(TASK_ID, DOC_ID);

        // 不碰 MinIO、不进索引管线
        verifyNoInteractions(minioStorageService, chunkService, embeddingService, docChunkRepository);
        // 任务取消: FAILED + 原因 + 完成时间, 且不回写文档
        ArgumentCaptor<IndexTask> captor = ArgumentCaptor.forClass(IndexTask.class);
        verify(indexTaskRepository).save(captor.capture());
        assertEquals(IndexTask.STATUS_FAILED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMsg().contains("已删除"),
                "取消原因应说明文档已删除: " + captor.getValue().getErrorMsg());
        assertNotNull(captor.getValue().getFinishedAt());
        verify(documentRepository, never()).save(any(Document.class));
    }

    @Test
    void 索引执行中文档被删_写分块前放弃_不复活文档() {
        // 第一次 findById(任务入口)返回活文档; 之后(updateDocumentStatus 与写分块前重查)返回已删文档
        // —— 模拟 embedding 耗时数秒期间文档被 DELETE 接口软删
        when(documentRepository.findById(DOC_ID))
                .thenReturn(Optional.of(doc(Document.STATUS_PROCESSING, false)),
                        Optional.of(doc(Document.STATUS_PROCESSING, true)));
        when(minioStorageService.download("1/uuid.pdf")).thenReturn("内容".getBytes());
        when(loaderService.load(any(InputStream.class), any(String.class))).thenReturn(
                ParseResult.builder()
                        .success(true)
                        .pages(List.of(ParseResult.PageContent.builder().pageNum(1).text("内容").build()))
                        .totalPages(1)
                        .build());
        when(chunkService.chunk(any())).thenReturn(List.of(
                ChunkResult.builder().chunkIndex(0).content("内容").pageNum(1).estimatedTokens(10).build()));
        when(embeddingService.embedBatch(any())).thenReturn(List.of(new float[1024]));
        givenTask();

        loadService.executeFromMinio(TASK_ID, DOC_ID);

        // 分块一条不写(旧版本不删、新数据不 insert) —— 否则检索会命中已删文档
        verifyNoInteractions(docChunkRepository);
        // 文档一次都不回写 —— 否则过期实体的 merge 会把软删标记冲掉(文档在列表复活)
        verify(documentRepository, never()).save(any(Document.class));
        // 任务最终取消(updateTaskStatus 的 PROCESSING 一次 + cancelTask 一次)
        ArgumentCaptor<IndexTask> captor = ArgumentCaptor.forClass(IndexTask.class);
        verify(indexTaskRepository, times(2)).save(captor.capture());
        assertEquals(IndexTask.STATUS_FAILED, captor.getValue().getStatus());
        assertTrue(captor.getValue().getErrorMsg().contains("已删除"),
                "取消原因应说明文档已删除: " + captor.getValue().getErrorMsg());
    }
}
