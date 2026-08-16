package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.LocalDateTime;

/**
 * 对话会话实体, 对应 PG 里的 conversation 表.
 * <p>
 * 一行 = 用户与某个大模型人格的【一次会话】(这里只存会话元信息):
 * 首轮对话后由大模型总结出 {@link #title}, {@link #totalTokens} 记录累计 token 数;
 * {@link #summary} + {@link #summarizedCount} 实现"长会话记忆"——窗口外的老消息被压缩成摘要, 不丢关键信息又不撑爆 token.
 * {@link #version} 是乐观锁版本号, 防止同一会话并发发消息时字段丢失更新.
 * 具体的每轮对话消息另存 message 子表.
 *
 * @author ckj
 */
@Entity
@Table(name = "conversation")
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    /** 发起会话的用户(关联 users.id) */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 本次会话使用的人格(关联 persona_fragment.persona_id) */
    @Column(name = "persona_id", nullable = false, length = 64)
    private String personaId;

    /** 本次会话绑定的知识库(关联 knowledge_base.id); null = 纯人格对话, 不做 RAG 检索 */
    @Column(name = "kb_id")
    private Long kbId;

    /** 会话标题: 首轮对话后由大模型总结生成; 首轮结束前可为空 */
    @Column(length = 200)
    private String title;

    /** 历史摘要: 窗口外的老消息压缩成的一段摘要, 实现长会话记忆 */
    @Column(columnDefinition = "text")
    private String summary;

    /** 已纳入摘要的消息条数(增量摘要的进度) */
    @Column(name = "summarized_count", nullable = false)
    private Integer summarizedCount = 0;

    /** 本会话累计消耗的 token 数(prompt + completion) */
    @Column(name = "total_tokens", nullable = false)
    private Integer totalTokens = 0;

    /** 乐观锁版本号: 由 JPA 自动维护, 并发更新冲突时抛 OptimisticLockingFailureException */
    @Version
    private Integer version;

    /** 会话发起时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /** 会话最后更新时间(每追加一轮对话刷新一次) */
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (summarizedCount == null) {
            summarizedCount = 0;
        }
        if (totalTokens == null) {
            totalTokens = 0;
        }
    }

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

    public Long getKbId() {
        return kbId;
    }

    public void setKbId(Long kbId) {
        this.kbId = kbId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Integer getSummarizedCount() {
        return summarizedCount;
    }

    public void setSummarizedCount(Integer summarizedCount) {
        this.summarizedCount = summarizedCount;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
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
        return "Conversation{" +
                "id=" + id +
                ", userId=" + userId +
                ", personaId='" + personaId + '\'' +
                ", kbId=" + kbId +
                ", title='" + title + '\'' +
                ", summaryLength=" + (summary == null ? 0 : summary.length()) +
                ", summarizedCount=" + summarizedCount +
                ", totalTokens=" + totalTokens +
                ", version=" + version +
                ", createdAt=" + createdAt +
                '}';
    }
}
