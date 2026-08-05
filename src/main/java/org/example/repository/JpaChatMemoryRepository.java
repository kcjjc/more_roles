package org.example.repository;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 把 message 表包装成 Spring AI 的 {@link ChatMemoryRepository}, 作为【窗口视图】:
 * {@link #findByConversationId} 只返回该会话最近 {@code WINDOW_SIZE} 条消息(正序),
 * 供 {@code MessageWindowChatMemory.get()} 取窗口喂给模型.
 * <p>
 * 注意:
 *  * 消息的【写入】不走这里 —— 由 {@code ChatService.saveMessage} 直接写 message 表(要携带 tokens,
 *    而 ChatMemory 抽象不携带 tokens). 所以 {@link #saveAll} 是空实现.
 *  * message 表始终存全量历史(业务展示/统计用), 这里只提供"最近 N 条"的窗口视图, 不删老消息.
 *
 * @author ckj
 */
@Component
public class JpaChatMemoryRepository implements ChatMemoryRepository {

    /** 窗口大小, 与 MessageWindowChatMemory.maxMessages 对齐(也与 findTop20 的 20 对齐) */
    public static final int WINDOW_SIZE = 20;

    private final MessageRepository messageRepository;

    public JpaChatMemoryRepository(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @Override
    public List<String> findConversationIds() {
        // 会话 id 由 conversation 表管理, 这里无需提供, 返回空列表即可
        return List.of();
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        // findTop20...Desc 取最近 20 条(倒序), 再反转成正序, 即窗口内消息
        List<org.example.entity.Message> recent = messageRepository
                .findTop20ByConversationIdOrderByCreatedAtDesc(Long.valueOf(conversationId));
        Collections.reverse(recent);
        return recent.stream().map(this::toAiMessage).toList();
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        // 空实现: 消息由 ChatService 直接写 message 表(需带 tokens). 详见类注释.
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        messageRepository.deleteByConversationId(Long.valueOf(conversationId));
    }

    /** DB 消息 → Spring AI 消息(历史里正常只有 user / assistant) */
    private Message toAiMessage(org.example.entity.Message m) {
        if (org.example.entity.Message.ROLE_ASSISTANT.equals(m.getRole())) {
            return new AssistantMessage(m.getContent());
        }
        return new UserMessage(m.getContent());
    }
}
