package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct(Reason + Act)显式循环执行器: 关闭框架内置的工具自动执行
 * ({@code internalToolExecutionEnabled=false}), 由本类手动驱动
 * "模型思考 → 请求工具(Action) → 本地执行 → 结果回传(Observation) → 再思考"循环,
 * 直到模型给出最终文本回复.
 * <p>
 * 相比框架内置循环的收益: 每步 Action/Observation 可观测(轨迹 {@link ReActStep} +
 * {@code [react]} 结构化日志)、逐轮累加 token(内置循环只暴露最后一轮 usage)、
 * 轮数上限熔断(耗尽后去掉工具强制收尾, 不阻断聊天).
 * <p>
 * 工具执行异常由框架默认异常处理器转成错误文本回传给模型自救
 * ({@code DefaultToolExecutionExceptionProcessor} 默认 alwaysThrow=false),
 * 与"路由器/检索永远不阻断聊天"的降级总则一致.
 * <p>
 * 记忆策略: 本类不挂 {@code MessageChatMemoryAdvisor}(advisor 的 before 每轮都会重复注入历史),
 * 历史由调用方从 chatMemory 取窗口传入; 工具中间消息(assistant toolCall / toolResponse)
 * 只活在本次循环内, 绝不落库 —— 与前置 RAG 命中不落库同一不变量.
 *
 * @author ckj
 */
@Service
public class ReActExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReActExecutor.class);

    /** 轨迹里 arguments/observation 的截断上限(完整内容在消息列表里) */
    private static final int STEP_MAX_CHARS = 200;

    private final ChatModel chatModel;
    /** 工具执行器: 与框架内置循环同一套机制, 只是循环步进由本类控制 */
    private final ToolCallingManager toolCallingManager = ToolCallingManager.builder().build();

    /** ReAct 循环轮数上限(一轮 = 一次"模型请求工具→执行→回传"); 达到后去掉工具强制收尾 */
    @Value("${agent.max-iterations:5}")
    private int maxIterations;

    public ReActExecutor(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 执行一次 ReAct 对话: 消息全量自管(system + 窗口历史 + 本轮 user, 循环中追加
     * assistant(toolCalls) 与 toolResponse), 每轮全量重发.
     *
     * @param system      system prompt(人格 + 摘要; agent 模式无前置命中段)
     * @param history     窗口历史(来自 chatMemory)
     * @param userContent 本轮用户消息
     * @param toolObjects 本次可用的工具实例(按会话动态组装, 如 RagTools.forConversation(...));
     *                    为空时模型无从发起工具调用, 等价于普通单次调用
     * @return 最终回复 + 全循环 token 累计 + 轨迹; truncated=true 表示达轮数上限被强制收尾
     */
    public ReActResult execute(String system, List<Message> history, String userContent, List<Object> toolObjects) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        if (history != null) {
            messages.addAll(history);
        }
        messages.add(new UserMessage(userContent));

        ChatOptions options = buildOptions(toolObjects);
        Prompt prompt = new Prompt(messages, options);
        ChatResponse response = chatModel.call(prompt);
        int tokens = extractTokens(response);

        List<ReActStep> steps = new ArrayList<>();
        int rounds = 0;
        boolean truncated = false;
        while (response.hasToolCalls()) {
            if (rounds >= maxIterations) {
                truncated = true;
                log.warn("[react] 达轮数上限 {}, 去掉工具强制收尾", maxIterations);
                prompt = new Prompt(messages);   // 不带 options: 模型只能给文本回复
                response = chatModel.call(prompt);
                tokens += extractTokens(response);
                break;
            }
            rounds++;
            long start = System.currentTimeMillis();
            ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);
            long elapsed = System.currentTimeMillis() - start;
            steps.addAll(extractSteps(toolResult.conversationHistory(), elapsed));
            messages = new ArrayList<>(toolResult.conversationHistory());
            prompt = new Prompt(messages, options);
            response = chatModel.call(prompt);
            tokens += extractTokens(response);
        }

        String reply = response.getResult() != null ? response.getResult().getOutput().getText() : null;
        return new ReActResult(reply, tokens, List.copyOf(steps), truncated);
    }

    /** 循环选项: 关掉内置自动执行; 工具实例经 MethodToolCallbackProvider 转成回调注入 */
    private ChatOptions buildOptions(List<Object> toolObjects) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .internalToolExecutionEnabled(false);
        if (toolObjects != null && !toolObjects.isEmpty()) {
            ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(toolObjects.toArray())
                    .build()
                    .getToolCallbacks();
            builder.toolCallbacks(callbacks);
        }
        return builder.build();
    }

    /**
     * 从工具执行后的会话历史尾部提取本轮轨迹: 倒序找最近的 assistant(toolCalls) 与
     * toolResponse, 按 toolCall id(缺 id 用工具名)配对 Action 与 Observation.
     */
    private List<ReActStep> extractSteps(List<Message> conversationHistory, long elapsed) {
        Map<String, String> observations = new HashMap<>();
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            if (conversationHistory.get(i) instanceof ToolResponseMessage toolResponse) {
                for (ToolResponseMessage.ToolResponse r : toolResponse.getResponses()) {
                    observations.put(r.id() != null ? r.id() : r.name(), r.responseData());
                }
                break;
            }
        }
        List<ReActStep> steps = new ArrayList<>();
        for (int i = conversationHistory.size() - 1; i >= 0; i--) {
            if (conversationHistory.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    String observation = observations.get(call.id() != null ? call.id() : call.name());
                    steps.add(new ReActStep(call.name(), truncate(call.arguments()),
                            truncate(observation), elapsed));
                    log.info("[react] 工具 {} args={} 耗时{}ms 结果: {}", call.name(),
                            truncate(call.arguments()), elapsed, truncate(observation));
                }
                break;
            }
        }
        return steps;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= STEP_MAX_CHARS ? text : text.substring(0, STEP_MAX_CHARS) + "…";
    }

    /** 从响应取本次消耗 token, 取不到兜底 0 */
    private int extractTokens(ChatResponse response) {
        try {
            if (response != null && response.getMetadata().getUsage() != null) {
                Integer total = response.getMetadata().getUsage().getTotalTokens();
                return total == null ? 0 : total;
            }
        } catch (Exception e) {
            log.debug("取 token 失败, 兜底 0", e);
        }
        return 0;
    }

    /** 一次 ReAct 对话的结果: 最终回复 + 全循环 token 累计 + 轨迹 */
    public record ReActResult(String reply, int tokens, List<ReActStep> steps, boolean truncated) {
    }
}
