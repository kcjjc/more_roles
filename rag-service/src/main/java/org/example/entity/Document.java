package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 文档实体, 对应 PG 里的 document 表.
 * <p>
 * 一个文档对应多个分块(doc_chunk). 这里存文档元信息 + MinIO 对象路径 + 索引状态,
 * 真正的向量数据在分块表. {@link #version} 是【业务版本号】(每次重建索引 +1,
 * 旧版本分块靠它识别后清理), 不是 JPA 乐观锁, 因此未加 {@code @Version}.
 *
 * @author ckj
 */
@Entity
@Table(name = "document")
public class Document {

    /** 文件类型: PDF */
    public static final String FILE_TYPE_PDF = "PDF";
    /** 文件类型: Word(docx) */
    public static final String FILE_TYPE_DOCX = "DOCX";
    /** 文件类型: Markdown */
    public static final String FILE_TYPE_MD = "MD";
    /** 文件类型: 纯文本 */
    public static final String FILE_TYPE_TXT = "TXT";

    /** 索引状态: 待处理 */
    public static final String STATUS_PENDING = "PENDING";
    /** 索引状态: 处理中 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    /** 索引状态: 索引完成 */
    public static final String STATUS_DONE = "DONE";
    /** 索引状态: 失败 */
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    /** 所属知识库(关联 knowledge_base.id) */
    @Column(name = "kb_id", nullable = false)
    private Long kbId;

    /** 文件名 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文件类型: {@link #FILE_TYPE_PDF} / {@link #FILE_TYPE_DOCX} / {@link #FILE_TYPE_MD} / {@link #FILE_TYPE_TXT} */
    @Column(name = "file_type", nullable = false, length = 20)
    private String fileType;

    /** 文件大小(字节数) */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** MinIO 中的对象路径 */
    @Column(name = "minio_path", nullable = false, length = 500)
    private String minioPath;

    /** 索引状态: {@link #STATUS_PENDING} / {@link #STATUS_PROCESSING} / {@link #STATUS_DONE} / {@link #STATUS_FAILED} */
    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    /** 失败原因(仅 FAILED 时填) */
    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    /** 索引后的分块数量 */
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    /** 向量化消耗的 token 数 */
    @Column(name = "token_count")
    private Integer tokenCount = 0;

    /** 文档版本号: 每次重建索引 +1, 旧版本分块靠它识别后清理 */
    @Column(name = "version", nullable = false)
    private Integer version = 1;

    /** 上传者(关联 users.id) */
    @Column(name = "uploaded_by", nullable = false)
    private Long uploadedBy;

    /** 上传时间 */
    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    /** 最近一次索引完成时间 */
    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    /**
     * 软删除标记: false=有效, true=已删除.
     * <p>字段名刻意不带 {@code is} 前缀, 字段名 = 属性名 = {@code deleted},
     * Spring Data 派生查询里的 {@code DeletedFalse} 解析零歧义; DB 列名 {@code is_deleted} 由 {@code @Column} 映射.
     */
    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;

    /**
     * 新增前自动填充: 上传时间与各字段默认值.
     * (document 表没有 updated_at, 因此不写 {@code @PreUpdate})
     */
    @PrePersist
    void prePersist() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = STATUS_PENDING;
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
        if (tokenCount == null) {
            tokenCount = 0;
        }
        if (version == null) {
            version = 1;
        }
        if (deleted == null) {
            deleted = false;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getMinioPath() {
        return minioPath;
    }

    public void setMinioPath(String minioPath) {
        this.minioPath = minioPath;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getUploadedBy() {
        return uploadedBy;
    }

    public void setUploadedBy(Long uploadedBy) {
        this.uploadedBy = uploadedBy;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public LocalDateTime getIndexedAt() {
        return indexedAt;
    }

    public void setIndexedAt(LocalDateTime indexedAt) {
        this.indexedAt = indexedAt;
    }

    public Boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public String toString() {
        return "Document{" +
                "id=" + id +
                ", kbId=" + kbId +
                ", fileName='" + fileName + '\'' +
                ", fileType='" + fileType + '\'' +
                ", fileSize=" + fileSize +
                ", status='" + status + '\'' +
                ", chunkCount=" + chunkCount +
                ", version=" + version +
                ", uploadedBy=" + uploadedBy +
                '}';
    }
}
