package org.example.repository;

import org.example.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 对话会话数据访问层.
 *
 * @author ckj
 */
public interface ConversationRepository extends JpaRepository<Conversation, Long> {

    /** 列出某用户【某人格下】的会话, 按最后更新时间倒序(选人格后看会话列表用). */
    List<Conversation> findByUserIdAndPersonaIdOrderByUpdatedAtDesc(Long userId, String personaId);

    /** 列出某用户的全部会话, 按最后更新时间倒序. */
    List<Conversation> findByUserIdOrderByUpdatedAtDesc(Long userId);
}
