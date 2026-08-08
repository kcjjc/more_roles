package org.example.repository;

import org.example.entity.DocChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文档分块数据访问层.
 * <p>
 * <b>仅用于分块的元数据 / 原文读取与清理.</b> 向量检索(embedding 列, 用 {@code <=>} 操作符)和
 * 全文检索(content_tsv)JPA 不支持, 请在 Service 层走原生 SQL / JdbcTemplate;
 * 分块【写入】也必须走原生 SQL —— 因为 embedding 列 NOT NULL, JPA 的 save 不会带它.
 *
 * @author ckj
 * @see DocChunk 类注释关于 embedding / content_tsv 未映射的说明
 */
public interface DocChunkRepository extends JpaRepository<DocChunk, Long> {

    /** 取某文档的全部分块, 按 chunk_index 正序(还原阅读顺序). */
    List<DocChunk> findByDocIdOrderByChunkIndexAsc(Long docId);

    /** 统计某文档的分块数. */
    long countByDocId(Long docId);

    /** 删除某文档【指定版本之外】的旧分块 —— 重建索引后清理旧版本用. */
    @Modifying
    @Transactional
    @Query("DELETE FROM DocChunk c WHERE c.docId = :docId AND c.docVersion <> :version")
    int deleteByDocIdAndDocVersionNot(@Param("docId") Long docId, @Param("version") Integer version);

    /** 删除某文档的全部分块(删文档时连带清理). */
    @Modifying
    @Transactional
    @Query("DELETE FROM DocChunk c WHERE c.docId = :docId")
    int deleteByDocId(@Param("docId") Long docId);
}
