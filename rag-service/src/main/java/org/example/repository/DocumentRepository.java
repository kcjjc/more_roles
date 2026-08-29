package org.example.repository;

import org.example.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 文档数据访问层.
 *
 * @author ckj
 */
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /** 列出某知识库下【未删除】的全部文档. */
    List<Document> findByKbIdAndDeletedFalse(Long kbId);

    /** 按 id + kbId 查【未删除】文档(删除前校验"文档存在且属于该库"用). */
    Optional<Document> findByIdAndKbIdAndDeletedFalse(Long id, Long kbId);

    /** 列出某知识库下【未删除】的文档, 按上传时间倒序(知识库文件列表用). */
    List<Document> findByKbIdAndDeletedFalseOrderByUploadedAtDesc(Long kbId);

    /** 列出某知识库下处于指定状态(且未删除)的文档 —— 看某个库的待处理/失败文档. */
    List<Document> findByKbIdAndStatusAndDeletedFalse(Long kbId, String status);

    /** 扫描所有处于指定状态(且未删除)的文档(跨知识库), 索引轮询捞活用. */
    List<Document> findByStatusAndDeletedFalse(String status);
}
