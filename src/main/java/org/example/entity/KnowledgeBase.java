package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 知识库实体, 对应 PG 里的 knowledge_base 表.
 * <p>
 * 一个用户可以创建多个知识库. 知识库本身只存元信息, 具体的文档和分块
 * 分别落在 document 和 doc_chunk 子表.
 *
 * @author ckj
 */
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    /** 知识库名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 描述/说明, 可为空 */
    @Column(columnDefinition = "text")
    private String description;

    /** 创建者(关联 users.id) */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 软删除标记: false=有效, true=已删除.
     * <p>字段名刻意不带 {@code is} 前缀 —— 这样字段名、JavaBean 属性名、
     * Spring Data 派生查询里的 {@code DeletedFalse} 三者一致, 零解析歧义;
     * DB 列名 {@code is_deleted} 由 {@code @Column} 映射.
     */
    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;

    /**
     * 新增前自动填充: 创建/更新时间、软删除默认值.
     * (项目未启用 JPA Auditing, 这里用生命周期回调手动填, 零额外配置)
     */
    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (deleted == null) {
            deleted = false;
        }
    }

    /** 更新前自动刷新 updated_at */
    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public String toString() {
        return "KnowledgeBase{" +
                "id=" + id +
                ", name='" + name + '\'' +
                ", createdBy=" + createdBy +
                ", deleted=" + deleted +
                ", createdAt=" + createdAt +
                '}';
    }
}
