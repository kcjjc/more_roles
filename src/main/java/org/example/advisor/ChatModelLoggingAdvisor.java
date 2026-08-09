package org.example.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话三段式日志 advisor —— onRequest / onResponse / onError, 对标 LangChain4j 的 ChatModelListener.
 * Advisor 没有专门的 onError 钩子, 这里用 try-catch 包住 {@code chain.nextCall} 等价实现.
 * <p>
 * <b>执行位置 (order):</b> {@link Ordered#LOWEST_PRECEDENCE} - 1 —— 即"最内层的用户 advisor".
 * Spring AI 1.1.2 的链按 order 升序 pop 执行, 终结点 {@code ChatModelCallAdvisor}(真正调 ChatModel 的那一个)
 * 固定占用 {@code LOWEST_PRECEDENCE}. 故本 advisor 取 {@code LOWEST_PRECEDENCE - 1}:
 * <ul>
 *   <li>不能取 {@code LOWEST_PRECEDENCE} —— 会和终结点 order 撞车, 经 deque 头插 + 稳定排序后
 *       本 advisor 可能排到终结点之后变成链尾, {@code nextCall()} 找不到下一个而抛
 *       "No CallAdvisors available to execute";</li>
 *   <li>取 {@code LOWEST_PRECEDENCE - 1} —— 排在终结点之前(能 nextCall 到它), 又远大于
 *       memory advisor(默认 {@code HIGHEST_PRECEDENCE + 1000}), 于是 onRequest 能看到
 *       memory 注入历史后的【最终请求】.</li>
 * </ul>
 * 默认 {@code internalToolExecutionEnabled=true} 时工具调用循环在 ChatModel 内部完成,
 * 本 advisor 整个请求只经过一次(onRequest 看含工具定义的最终 prompt, onResponse 看最终回复).
 *
 * @author ckj
 */
@Component
public class ChatModelLoggingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(ChatModelLoggingAdvisor.class);

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        onRequest(request);

        ChatClientResponse response;
        try {
            response = chain.nextCall(request);
        } catch (RuntimeException e) {
            onError(e);
            throw e;
        }

        onResponse(response);
        return response;
    }

    // ============ onRequest ============

    private void onRequest(ChatClientRequest request) {
        log.info("\n===== 【发送给模型】 =====");
        for (Message m : request.prompt().getInstructions()) {
            if (m instanceof ToolResponseMessage trm) {
                // 工具结果消息 getText() 为 null, 必须走 getResponses()
                trm.getResponses().forEach(r ->
                        log.info("[TOOL结果] {} -> {}", r.name(), r.responseData()));
            } else {
                log.info("[{}] {}", m.getMessageType(), m.getText());
            }
        }
        List<String> tools = availableTools(request);
        if (!tools.isEmpty()) {
            log.info("可用工具：{}", tools);
        }
    }

    /** 尽力取工具名; 默认配置下工具在 ChatModel 内部, options 里可能没有 —— 取不到就空 */
    private List<String> availableTools(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        if (options instanceof ToolCallingChatOptions tco) {
            List<ToolCallback> callbacks = tco.getToolCallbacks();
            if (callbacks == null || callbacks.isEmpty()) {
                return List.of();
            }
            return callbacks.stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(ToolDefinition::name)
                    .toList();
        }
        return List.of();
    }

    // ============ onResponse ============

    private void onResponse(ChatClientResponse response) {
        log.info("\n===== 【模型返回】 =====");
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            log.info("(空响应)");
            return;
        }
        Generation result = chatResponse.getResult();
        if (result == null || result.getOutput() == null) {
            log.info("(无生成结果)");
            return;
        }
        AssistantMessage msg = result.getOutput();
        if (msg.getText() != null) {
            log.info("回答：{}", msg.getText());
        }
        if (msg.hasToolCalls()) {
            for (AssistantMessage.ToolCall t : msg.getToolCalls()) {
                log.info("调用工具：{}，参数：{}", t.name(), t.arguments());
            }
        }
        Integer totalTokens = totalTokens(chatResponse);
        if (totalTokens != null) {
            log.info("Token：{}", totalTokens);
        }
    }

    /** 取本次总 token, 取不到返回 null */
    private Integer totalTokens(ChatResponse chatResponse) {
        try {
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                return chatResponse.getMetadata().getUsage().getTotalTokens();
            }
        } catch (Exception e) {
            log.debug("取 token 失败", e);
        }
        return null;
    }

    // ============ onError ============

    private void onError(Throwable e) {
        log.error("\n===== 【模型报错】 =====", e);
    }
}
