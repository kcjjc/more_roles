package org.example.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.example.common.Result;
import org.example.entity.Conversation;
import org.example.service.ChatService;
import org.example.service.ChatService.ConversationDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 对话接口: 列会话 / 新建会话 / 会话详情 / 发消息 / 删会话.
 * <p>
 * 所有接口都需登录(由 SaTokenConfigure 拦截 /api/chat/**), userId 一律从登录态取.
 *
 * @author ckj
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /** 新建会话请求体: personaId 必填; kbId 可选, 传了即绑定知识库(会话内消息按需做 RAG 检索) */
    public record CreateConversationRequest(String personaId, Long kbId) {
    }

    /** 发消息请求体 */
    public record ChatRequest(String content) {
    }

    /**
     * 列出某人格下的会话: GET /api/chat/personas/{personaId}/conversations
     * <p>
     * 选人格后调它; 返回空数组就说明还没对话过, 前端显示"新建对话".
     */
    @GetMapping("/personas/{personaId}/conversations")
    public Result<List<Conversation>> listConversations(@PathVariable String personaId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(chatService.getConversations(userId, personaId));
    }

    /**
     * 新建会话: POST /api/chat/conversations
     * body: {"personaId":"xxx", "kbId":1}   (kbId 可省略 = 纯人格对话)
     */
    @PostMapping("/conversations")
    public Result<?> createConversation(@RequestBody CreateConversationRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        try {
            Conversation conv = chatService.createConversation(userId, req.personaId(), req.kbId());
            return Result.ok(Map.of(
                    "conversationId", conv.getId(),
                    "personaId", conv.getPersonaId(),
                    "kbId", conv.getKbId() == null ? "" : conv.getKbId()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 会话详情(含历史消息): GET /api/chat/conversations/{id}
     */
    @GetMapping("/conversations/{id}")
    public Result<ConversationDetail> getConversation(@PathVariable("id") Long conversationId) {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(chatService.getConversationDetail(userId, conversationId));
    }

    /**
     * 发消息对话: POST /api/chat/conversations/{id}/messages
     * body: {"content":"你好"}
     * → 返回模型回复
     */
    @PostMapping("/conversations/{id}/messages")
    public Result<?> chat(@PathVariable("id") Long conversationId, @RequestBody ChatRequest req) {
        Long userId = StpUtil.getLoginIdAsLong();
        try {
            String reply = chatService.chat(userId, conversationId, req.content());
            return Result.ok(Map.of("reply", reply));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 删除会话(连带消息): DELETE /api/chat/conversations/{id}
     */
    @DeleteMapping("/conversations/{id}")
    public Result<?> deleteConversation(@PathVariable("id") Long conversationId) {
        Long userId = StpUtil.getLoginIdAsLong();
        try {
            chatService.deleteConversation(userId, conversationId);
            return Result.ok(null);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }
}
