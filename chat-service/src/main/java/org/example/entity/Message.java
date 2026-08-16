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
 * 对话消息实体, 对应 PG 里的 message 表.
 * <p>
 * 一个 {@link Conversation} 有多条消息, 每条消息一行. {@link #role} 用小写, 与大模型 API 约定一致,
 * 取出来可直接拼成 Spring AI 的消息喂给模型.
 *
 * @author ckj
 */
@Entity
@Table(name = "message")
public class Message {

    /** 角色: 用户提问 */
    public static final String ROLE_USER = "user";
    /** 角色: 模型回复 */
    public static final String ROLE_ASSISTANT = "assistant";
    /** 角色: 系统提示 */
    public static final String ROLE_SYSTEM = "system";
    /** 角色: 工具调用结果 */
    public static final String ROLE_TOOL = "tool";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    /** 所属会话(关联 conversation.id) */
    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 消息角色: {@link #ROLE_USER} / {@link #ROLE_ASSISTANT} / {@link #ROLE_SYSTEM} / {@link #ROLE_TOOL} */
    @Column(nullable = false, length = 20)
    private String role;

    /** 消息内容, TEXT 无长度限制 */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 本条消息消耗的 token 数 */
    @Column(nullable = false)
    private Integer tokens = 0;

    /** 消息创建时间(也用于排序还原对话顺序) */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (tokens == null) {
            tokens = 0;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getTokens() {
        return tokens;
    }

    public void setTokens(Integer tokens) {
        this.tokens = tokens;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "Message{" +
                "id=" + id +
                ", conversationId=" + conversationId +
                ", role='" + role + '\'' +
                ", contentLength=" + (content == null ? 0 : content.length()) +
                ", tokens=" + tokens +
                ", createdAt=" + createdAt +
                '}';
    }
}
