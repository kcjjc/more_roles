package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 文档分块实体, 对应 PG 里的 doc_chunk 表.
 * <p>
 * 每条记录是一个可检索的最小单元: {@link #content} 是分块原文, {@link #docId} / {@link #kbId}
 * 冗余存储便于按文档/知识库过滤.
 * <p>
 * <b>注意: 本表还有两列未映射到本实体 ——</b>
 * <ul>
 *   <li>{@code embedding VECTOR(1024)} —— 向量列. JPA/Hibernate 原生不支持该类型,
 *       且向量检索依赖 PG 的 {@code <=>} 操作符(JPQL 写不了). 因此向量的写入与相似度检索
 *       应走原生 SQL / JdbcTemplate, 不走本 Repository.</li>
 *   <li>{@code content_tsv TSVECTOR} —— 全文检索列, 由数据库触发器自动维护, 应用层无需读写.</li>
 * </ul>
 * 所以本实体只承载分块的【元数据与原文】, {@link #content} 的全文检索、embedding 的相似度检索
 * 都交给检索层单独处理. 正因如此, <b>不要用 {@code save()} 直接插入分块</b>:
 * embedding 列是 NOT NULL, 而 INSERT 语句不带它会违反约束 —— 分块入库必须走带 embedding 的原生 SQL.
 *
 * @author ckj
 */
@Entity
@Table(name = "doc_chunk")
public class DocChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    /** 所属文档(关联 document.id) */
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    /** 冗余: 所属知识库, 检索时避免 JOIN */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 在文档中的顺序(0-based) */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 分块原文 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 来自文档第几页(PDF 专用), 可为空 */
    @Column(name = "page_num")
    private Integer pageNum;

    /** 所在章节标题(如果能识别), 可为空 */
    @Column(name = "section_title", length = 500)
    private String sectionTitle;

    /** 该块的 token 估算数 */
    @Column(name = "token_count", nullable = false)
    private Integer tokenCount = 0;

    /** 对应的文档版本号(冗余), 重建索引后删除旧版本时用 */
    @Column(name = "doc_version", nullable = false)
    private Integer docVersion;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * 向量字段，使用 PGVector 的 vector 类型。
     * Hibernate 6.4+ 通过 hibernate-vector 模块原生支持 pgvector：
     * @JdbcTypeCode(SqlTypes.VECTOR) 告诉 Hibernate 这是向量类型，
     * columnDefinition = "vector(1024)" 指定维度（对应 text-embedding-v3 的 1024 维）。
     */
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Column(name = "embedding", columnDefinition = "vector(1024)")
    private float[] embedding;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (tokenCount == null) {
            tokenCount = 0;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getDocVersion() {
        return docVersion;
    }

    public void setDocVersion(Integer docVersion) {
        this.docVersion = docVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "DocChunk{" +
                "id=" + id +
                ", docId=" + docId +
                ", kbId=" + kbId +
                ", chunkIndex=" + chunkIndex +
                ", contentLength=" + (content == null ? 0 : content.length()) +
                ", pageNum=" + pageNum +
                ", docVersion=" + docVersion +
                '}';
    }

    public float[] getEmbedding() {
        return embedding;
    }
    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }
}
