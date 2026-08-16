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
 * 人格信息【分片】实体, 对应 PG 里的 persona_fragment 表.
 * <p>
 * 一条人格信息若超过 {@link #CONTENT_MAX_LENGTH} 字, 会被切成多行存储:
 * 同一条人格的多行共享同一个 {@code personaId}, 用 {@code seq} 标记顺序,
 * 取出后按 {@code seq} 升序拼接即可还原原文.
 *
 * @author ckj
 */
@Entity
@Table(name = "persona_fragment")
public class PersonaFragment {

    /** 单片内容最大字符数, 与表结构 VARCHAR(4000) 保持一致 */
    public static final int CONTENT_MAX_LENGTH = 4000;

    /** 状态: 有效(未删除) */
    public static final int STATUS_ACTIVE = 1;
    /** 状态: 已软删除 */
    public static final int STATUS_DELETED = 0;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 标识同一条人格(多片共享), 一般存去掉横线的 UUID */
    @Column(name = "persona_id", nullable = false, length = 64)
    private String personaId;

    /** 分片序号, 从 0 开始, 决定拼接顺序 */
    @Column(nullable = false)
    private Integer seq;

    /** 人格名称(冗余字段, 每片都存一份, 列表展示用) */
    @Column(length = 100)
    private String name;

    /** 内容片段, 单片最多 {@value #CONTENT_MAX_LENGTH} 字 */
    @Column(nullable = false, length = CONTENT_MAX_LENGTH)
    private String content;

    /** 软删除标记: {@link #STATUS_ACTIVE}=有效, {@link #STATUS_DELETED}=已删除 */
    @Column(nullable = false)
    private Integer status = STATUS_ACTIVE;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * 新增前自动填充: 创建/更新时间、状态默认值.
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
        if (status == null) {
            status = STATUS_ACTIVE;
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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPersonaId() {
        return personaId;
    }

    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
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

    @Override
    public String toString() {
        return "PersonaFragment{" +
                "id=" + id +
                ", userId=" + userId +
                ", personaId='" + personaId + '\'' +
                ", seq=" + seq +
                ", name='" + name + '\'' +
                ", contentLength=" + (content == null ? 0 : content.length()) +
                ", status=" + status +
                '}';
    }
}
