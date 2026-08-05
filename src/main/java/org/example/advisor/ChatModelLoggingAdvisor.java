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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对应 LangChain4j 的 ChatModelListener(onRequest / onResponse / onError) 三段式.
 * Advisor 没有专门的 onError 回调, 用 try-catch 包住链调用等价实现.
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

    /**
     * 靠近模型(最内层): onRequest 能看到 memory advisor 注入历史/摘要后的【最终请求】.
     * 默认 internalToolExecutionEnabled=true 时工具循环在 ChatModel 内部完成,
     * 本 advisor 只走一次(并非工具每一轮都经过).
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
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
                // 工具结果消息 getText() 是 null, 必须走 getResponses()
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
            tco.getToolCallbacks();
            return tco.getToolCallbacks().stream()
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
            return;
        } else {
            chatResponse.getResult();
        }
        AssistantMessage msg = chatResponse.getResult().getOutput();
        if (msg.getText() != null) {
            log.info("回答：{}", msg.getText());
        }
        if (msg.hasToolCalls()) {
            for (AssistantMessage.ToolCall t : msg.getToolCalls()) {
                log.info("调用工具：{}，参数：{}", t.name(), t.arguments());
            }
        }
    }

    // ============ onError ============

    private void onError(Throwable e) {
        log.error("===== 【模型报错】 =====", e);
    }
}
