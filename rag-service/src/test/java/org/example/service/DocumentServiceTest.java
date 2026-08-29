package org.example.service;

import org.example.entity.Document;
import org.example.entity.KnowledgeBase;
import org.example.repository.DocChunkRepository;
import org.example.repository.DocumentRepository;
import org.example.repository.KnowledgeBaseRepository;
import org.example.service.DocumentService.DeleteResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * DocumentService 删除文档的纯单测(全部依赖 mock):
 * 覆盖 成功删除的三段语义(物理删分块/软删文档/清 MinIO) / 三种校验拒绝 / MinIO 删除失败容忍.
 *
 * @author ckj
 */
class DocumentServiceTest {

    private static final Long KB_ID = 1L;
    private static final Long DOC_ID = 7L;
    private static final Long USER_ID = 9L;

    private final KnowledgeBaseRepository knowledgeBaseRepository = mock(KnowledgeBaseRepository.class);
    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocChunkRepository docChunkRepository = mock(DocChunkRepository.class);
    private final MinioStorageService minioStorageService = mock(MinioStorageService.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final DocumentService service = new DocumentService(
            knowledgeBaseRepository, documentRepository, docChunkRepository,
            mock(DocumentLoaderService.class), minioStorageService,
            mock(IndexService.class), transactionTemplate);

    DocumentServiceTest() {
        // 事务模板 stub: 直接执行回调(等价于"短事务提交成功"), 不真的开事务
        doAnswer(inv -> {
            Consumer<TransactionStatus> action = inv.getArgument(0);
            action.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    /** 知识库归属校验放行(kbId=1 属于 userId=9) */
    private void givenOwnedKb() {
        when(knowledgeBaseRepository.findByIdAndCreatedByAndDeletedFalse(KB_ID, USER_ID))
                .thenReturn(Optional.of(mock(KnowledgeBase.class)));
    }

    /** 一个 DONE 状态的 PDF 文档(minioPath 固定, 便于 verify) */
    private static Document givenDoc(String status) {
        Document d = new Document();
        d.setId(DOC_ID);
        d.setKbId(KB_ID);
        d.setFileName("员工手册.pdf");
        d.setFileType(Document.FILE_TYPE_PDF);
        d.setFileSize(2048L);
        d.setMinioPath("1/uuid.pdf");
        d.setStatus(status);
        return d;
    }

    @Test
    void 成功删除_DONE文档_物理删分块_软删文档_清理MinIO对象() {
        givenOwnedKb();
        when(documentRepository.findByIdAndKbIdAndDeletedFalse(DOC_ID, KB_ID))
                .thenReturn(Optional.of(givenDoc(Document.STATUS_DONE)));

        DeleteResult result = service.deleteFile(KB_ID, DOC_ID, USER_ID);

        // 分块物理删(检索立即不命中的唯一保障)
        verify(docChunkRepository).deleteByDocId(DOC_ID);
        // 文档软删
        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertTrue(captor.getValue().isDeleted(), "文档应被软删(deleted=true)");
        // MinIO 对象清理
        verify(minioStorageService).delete("1/uuid.pdf");
        assertEquals(new DeleteResult(DOC_ID, "员工手册.pdf"), result);
    }

    @Test
    void 知识库不存在_抛出IllegalArgumentException() {
        when(knowledgeBaseRepository.findByIdAndCreatedByAndDeletedFalse(KB_ID, USER_ID))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteFile(KB_ID, DOC_ID, USER_ID));
        assertEquals("知识库不存在: kbId=1", ex.getMessage());
        verifyNoInteractions(docChunkRepository, minioStorageService);
    }

    @Test
    void 文档不存在或已删除_抛出IllegalArgumentException() {
        givenOwnedKb();
        // 覆盖两种场景: docId 属于别的库 / 已软删(重复删除)
        when(documentRepository.findByIdAndKbIdAndDeletedFalse(DOC_ID, KB_ID))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteFile(KB_ID, DOC_ID, USER_ID));
        assertEquals("文档不存在: docId=7", ex.getMessage());
        verifyNoInteractions(docChunkRepository, minioStorageService);
    }

    @Test
    void PENDING状态拒绝删除() {
        assertIndexingRejected(Document.STATUS_PENDING);
    }

    @Test
    void PROCESSING状态拒绝删除() {
        assertIndexingRejected(Document.STATUS_PROCESSING);
    }

    private void assertIndexingRejected(String status) {
        givenOwnedKb();
        when(documentRepository.findByIdAndKbIdAndDeletedFalse(DOC_ID, KB_ID))
                .thenReturn(Optional.of(givenDoc(status)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteFile(KB_ID, DOC_ID, USER_ID));
        assertEquals("文档正在索引中, 请稍后再试", ex.getMessage());
        verifyNoInteractions(docChunkRepository, minioStorageService, transactionTemplate);
    }

    @Test
    void MinIO删除失败_不影响删除结果() {
        givenOwnedKb();
        when(documentRepository.findByIdAndKbIdAndDeletedFalse(DOC_ID, KB_ID))
                .thenReturn(Optional.of(givenDoc(Document.STATUS_DONE)));
        doThrow(new RuntimeException("MinIO 删除文件失败")).when(minioStorageService).delete("1/uuid.pdf");

        // 容忍语义: DB 已删定, 对象清理失败仅告警, 不向外抛
        DeleteResult result = service.deleteFile(KB_ID, DOC_ID, USER_ID);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository).save(captor.capture());
        assertTrue(captor.getValue().isDeleted(), "MinIO 失败不应回滚软删");
        assertEquals(new DeleteResult(DOC_ID, "员工手册.pdf"), result);
    }
}
