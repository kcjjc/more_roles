package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 检索路由器: 判断本轮消息【是否需要查知识库】, 并把消息【改写成适合检索的完整查询】.
 * <p>
 * 这是绑库会话每条消息的第一次(小输出) LLM 调用, 在主回复之前:
 * <ul>
 *   <li>need=false(闲聊/算术/常识) → 跳过 embedding 与检索, 直接进主调用;</li>
 *   <li>need=true → 用改写后的查询检索 —— 核心价值在多轮追问: "那后来呢"这类指代性消息
 *       拿原话检索基本搜不到东西, 路由器结合最近几轮对话补全指代;</li>
 *   <li><b>降级总则: 路由器永远不阻断聊天</b> —— 调用失败 / 输出不可解析 / 开关关闭,
 *       一律退回"拿原始消息检索"(即无路由的总是检索行为).</li>
 * </ul>
 * 独立小输出调用, 不挂 {@code ChatModelLoggingAdvisor}(每次对话都发生, 挂上会让 INFO 日志翻倍),
 * 用 ChatService 侧的一行 {@code [route]} 结构化日志代替.
 *
 * @author ckj
 */
@Service
public class RagRouterService {

    private static final Logger log = LoggerFactory.getLogger(RagRouterService.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 宽松提取模型输出里的 JSON 对象(模型可能包代码围栏或带前后缀文字), 贪婪匹配首 { 到尾 } */
    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}");

    /** 单轮对话文本截断上限: 路由只需要指代上下文, 不需要全文 */
    private static final int TURN_MAX_CHARS = 200;

    private static final String SYSTEM_PROMPT = """
            你是一个检索路由器，负责判断【当前消息】是否需要查询角色资料库，并输出决策。
            只输出一行 JSON，格式：{"need": true, "query": "检索用句子"}，不要输出任何其他内容。
            判断规则：
            1. 涉及角色设定、背景故事、世界观、资料库文档中的事实性内容 → need=true
            2. 闲聊、问候、算术、常识问题、创作要求 → need=false
            3. need=true 时，把当前消息改写成一句独立完整的检索查询：结合最近对话补全代词指代，不超过50字，脱离对话上下文也能看懂
            4. need=false 时，query 输出空字符串
            示例：
            最近对话：
            用户：星野是谁？
            助手：她是住在灯塔里的守夜人，童年随船队出海。
            当前消息：那后来呢？
            输出：{"need": true, "query": "星野守夜人后来的经历"}
            """;

    private final ChatClient chatClient;

    @Value("${rag.route.enabled:true}")
    private boolean enabled;

    /** 路由用的模型; 空 = 用主 chat 模型 */
    @Value("${rag.route.model:}")
    private String routeModel;

    /** 喂给路由器的最近对话轮数(含助手回复, 指代上下文主要在上一轮回答里) */
    @Value("${rag.route.context-turns:6}")
    private int contextTurns;

    public RagRouterService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /** 路由决策: need=是否检索; query=检索句(改写后); fallback=true 表示非正常判定(降级/开关关闭); tokens=本次路由消耗 */
    public record RouteDecision(boolean need, String query, boolean fallback, int tokens) {

        /** 降级决策: 总是检索, 用原始消息当查询(即"无路由"的老行为) */
        static RouteDecision always(String rawMessage) {
            return new RouteDecision(true, rawMessage, true, 0);
        }

        RouteDecision withTokens(int newTokens) {
            return new RouteDecision(need, query, fallback, newTokens);
        }
    }

    /**
     * 对一条用户消息做路由决策.
     *
     * @param personaName 人格名称(改写时还原指代用, 如"她"→具体角色名)
     * @param recentTurns 最近窗口消息(来自 chatMemory, 只取尾部 contextTurns 轮)
     * @param userMessage 本轮用户消息原文
     */
    public RouteDecision route(String personaName, List<Message> recentTurns, String userMessage) {
        if (!enabled) {
            return RouteDecision.always(userMessage);   // 开关关闭: 短路回"总是查原话"
        }
        int tokens = 0;
        try {
            var callResult = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(personaName, recentTurns, userMessage))
                    .options(buildOptions())
                    .call();
            tokens = extractTokens(callResult.chatResponse());
            return parseRouteOutput(callResult.content(), userMessage).withTokens(tokens);
        } catch (Exception e) {
            log.warn("[route] 路由调用失败, 退回拿原话检索: {}", e.getMessage());
            return new RouteDecision(true, userMessage, true, tokens);
        }
    }

    // ---------- prompt 与参数 ----------

    private String buildUserPrompt(String personaName, List<Message> recentTurns, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("角色：").append(personaName == null || personaName.isBlank() ? "未知角色" : personaName)
          .append('\n');
        sb.append("最近对话：\n");
        if (recentTurns == null || recentTurns.isEmpty()) {
            sb.append("(无)\n");
        } else {
            int from = Math.max(0, recentTurns.size() - contextTurns);
            for (int i = from; i < recentTurns.size(); i++) {
                Message m = recentTurns.get(i);
                if (m instanceof UserMessage) {
                    sb.append("用户：");
                } else if (m instanceof AssistantMessage) {
                    sb.append("助手：");
                } else {
                    continue;   // 历史里正常只有 user/assistant
                }
                sb.append(truncate(m.getText())).append('\n');
            }
        }
        sb.append("当前消息：").append(truncate(userMessage));
        return sb.toString();
    }

    /** 路由要小而快: 低温度求稳定, 限制输出长度, 可选换更便宜的路由模型 */
    private OpenAiChatOptions buildOptions() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                .temperature(0.1)
                .maxTokens(80);
        if (routeModel != null && !routeModel.isBlank()) {
            builder.model(routeModel.trim());
        }
        return builder.build();
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= TURN_MAX_CHARS ? text : text.substring(0, TURN_MAX_CHARS) + "…";
    }

    // ---------- 解析(包内可见供单测) ----------

    /**
     * 解析路由模型输出. 任何不合法(空/无 JSON/字段缺失/截断)都降级为 always(原话检索),
     * 不向上抛异常 —— 宁可多查一次, 不让路由器拦住对话.
     */
    static RouteDecision parseRouteOutput(String output, String rawMessage) {
        if (output == null || output.isBlank()) {
            return RouteDecision.always(rawMessage);
        }
        try {
            Matcher matcher = JSON_BLOCK.matcher(output);
            if (!matcher.find()) {
                return RouteDecision.always(rawMessage);
            }
            JsonNode node = OBJECT_MAPPER.readTree(matcher.group());
            boolean need = node.path("need").asBoolean(false);
            String query = node.path("query").asText("").trim();
            if (need && query.isEmpty()) {
                query = rawMessage;   // 判了要查却没给句子: 用原话
            }
            return new RouteDecision(need, query, false, 0);
        } catch (Exception e) {
            return RouteDecision.always(rawMessage);
        }
    }

    /** 从响应取本次消耗 token, 取不到兜底 0 */
    private int extractTokens(ChatResponse response) {
        try {
            if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                Integer total = response.getMetadata().getUsage().getTotalTokens();
                return total == null ? 0 : total;
            }
        } catch (Exception e) {
            log.debug("取路由 token 失败, 兜底 0", e);
        }
        return 0;
    }
}
