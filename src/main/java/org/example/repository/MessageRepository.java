package org.example.repository;

import org.example.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 对话消息数据访问层.
 *
 * @author ckj
 */
public interface MessageRepository extends JpaRepository<Message, Long> {

    /** 取某会话的全部消息, 按时间正序(还原对话顺序, 喂给大模型做上下文). */
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /** 取某会话【最近 20 条】消息, 按时间倒序(窗口视图, JpaChatMemoryRepository 用; 取出后反转成正序). */
    List<Message> findTop20ByConversationIdOrderByCreatedAtDesc(Long conversationId);

    /** 取某会话【前 N 条】消息(按时间正序), 配 Pageable 做增量摘要: 只读窗口外那批, 不读全量. */
    List<Message> findByConversationIdOrderByCreatedAtAsc(Long conversationId, Pageable pageable);

    /** 统计某会话的消息条数(增量摘要判断窗口外消息时用, 比 COUNT 后再读全量轻). */
    long countByConversationId(Long conversationId);

    /** 删除某会话下的全部消息(删会话时连带清理). */
    @Modifying
    @Query("DELETE FROM Message m WHERE m.conversationId = :conversationId")
    void deleteByConversationId(@Param("conversationId") Long conversationId);
}
