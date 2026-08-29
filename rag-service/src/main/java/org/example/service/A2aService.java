package org.example.service;

import lombok.extern.slf4j.Slf4j;
import org.example.common.a2a.A2aArtifact;
import org.example.common.a2a.A2aMessage;
import org.example.common.a2a.A2aSendMessageRequest;
import org.example.common.a2a.A2aSendMessageResponse;
import org.example.common.a2a.A2aTask;
import org.example.common.a2a.A2aTaskState;
import org.example.common.a2a.A2aTextPart;
import org.example.service.RagService.AskResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A2A Server 的编排核心: 把一条 A2A 消息变成一个 Task.
 * <p>
 * 协议语义与既有业务的对应 —— agent 视角下这是一个<b>不透明的知识库专家</b>:
 * 收到 message:send → 提取 parts 文本 → 复用 {@link RagService#ask}(检索 + 独立 LLM 回答,
 * 带防幻觉约束) → 产出 {@link A2aArtifact}(规范 3.7: 结果放 artifact 不放 message) →
 * 同步返回 {@code TASK_STATE_COMPLETED} 的 Task.
 * <p>
 * 失败语义: 任何异常(检索空/模型挂)不抛 HTTP 错误, 而是返回 {@code TASK_STATE_FAILED}
 * 的 Task(任务失败是协议内的合法状态, 与 HTTP 5xx 不同层) —— 调用方(模型)看到失败文案自行兜底.
 * kbId 通过 message.metadata 传递(服务间已由 API key 鉴权, 绝不让模型控制).
 *
 * @author ckj
 */
@Service
@Slf4j
public class A2aService {

    private final RagService ragService;
    private final A2aTaskStore taskStore;

    public A2aService(RagService ragService, A2aTaskStore taskStore) {
        this.ragService = ragService;
        this.taskStore = taskStore;
    }

    /** 处理一条 message:send, 返回终态 Task(本实现同步完成, 无中间态) */
    public A2aSendMessageResponse handleSend(A2aSendMessageRequest request) {
        String query = extractText(request);
        Long kbId = extractKbId(request);
        String taskId = UUID.randomUUID().toString();

        A2aTask task;
        try {
            AskResult result = ragService.ask(query, kbId, null);   // topK=null → rag 侧默认配置
            task = completedTask(taskId, result, kbId);
            log.info("[a2a] task={} kbId={} completed, answer {} 字", taskId, kbId, result.answer().length());
        } catch (Exception e) {
            log.warn("[a2a] task={} kbId={} failed: {}", taskId, kbId, e.getMessage());
            task = failedTask(taskId, "知识库问答失败: " + e.getMessage());
        }
        taskStore.put(task);
        return A2aSendMessageResponse.ofTask(task);
    }

    /** 查询任务; 不存在返回 empty(由 Controller 转规范的 TaskNotFoundError) */
    public A2aTask getTask(String taskId) {
        return taskStore.get(taskId).orElse(null);
    }

    // ---------- 组装 ----------

    /** 拼接 message.parts 里的全部 TextPart(多段文本以换行连接); 空则给失败 Task */
    private String extractText(A2aSendMessageRequest request) {
        if (request == null || request.message() == null || request.message().parts() == null) {
            return "";
        }
        return request.message().parts().stream()
                .filter(p -> p != null && p.text() != null && !p.text().isBlank())
                .map(A2aTextPart::text)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /** kbId 从 message.metadata 取(跨协议的业务参数通道); 缺省 = 全局检索 */
    private Long extractKbId(A2aSendMessageRequest request) {
        if (request.message() == null || request.message().metadata() == null) {
            return null;
        }
        Object v = request.message().metadata().get("kbId");
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private A2aTask completedTask(String taskId, AskResult result, Long kbId) {
        String contextId = UUID.randomUUID().toString();
        return new A2aTask(taskId, contextId,
                new A2aTask.A2aTaskStatus(A2aTaskState.COMPLETED, null, now()),
                List.of(new A2aArtifact(UUID.randomUUID().toString(), "kb-answer",
                        List.of(new A2aTextPart(formatAnswer(result))))));
    }

    private A2aTask failedTask(String taskId, String reason) {
        return new A2aTask(taskId, UUID.randomUUID().toString(),
                new A2aTask.A2aTaskStatus(A2aTaskState.FAILED, statusMessage(reason), now()),
                null);
    }

    /** 失败原因放进 status.message(规范: 状态消息用于说明任务进展/失败) */
    private A2aMessage statusMessage(String reason) {
        return new A2aMessage(A2aMessage.ROLE_AGENT, List.of(new A2aTextPart(reason)),
                UUID.randomUUID().toString(), null, null, null);
    }

    /** 回答 + 来源摘要拼成单段文本: 调用方(模型)既能拿到结论也能核验依据 */
    private String formatAnswer(AskResult result) {
        StringBuilder sb = new StringBuilder(result.answer());
        if (result.sources() != null && !result.sources().isEmpty()) {
            sb.append("\n\n[来源片段]\n");
            for (int i = 0; i < result.sources().size(); i++) {
                var hit = result.sources().get(i);
                sb.append('[').append(i + 1).append("] ").append(hit.content()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 规范 5.6.1: 时间戳 ISO-8601 UTC(Instant.toString() 即带毫秒的 Z 结尾格式) */
    private static String now() {
        return Instant.now().toString();
    }
}
