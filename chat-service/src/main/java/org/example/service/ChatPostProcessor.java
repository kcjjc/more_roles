package org.example.service;

import org.example.advisor.ChatModelLoggingAdvisor;
import org.example.config.ChatMemoryConfig;
import org.example.entity.Conversation;
import org.example.entity.Message;
import org.example.repository.ConversationRepository;
import org.example.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 对话的【后台副作用】处理器: 增量摘要 / 标题生成.
 * <p>
 * 这些 LLM 调用对【本次回复】无贡献, 由 {@link ChatService#chat} 在主交互落库后异步投递,
 * 不阻塞用户拿到回复. 两个入口都靠"游标 / 空值判断"幂等推进, 失败或应用崩溃都不丢 ——
 * 下次 chat 会再次投递并重做未完成部分.
 * <p>
 * 独立 Bean: {@code @Async} 必须跨 Bean 走代理才生效, 不能放回 ChatService 由其内部调用.
 *
 * @author ckj
 */
@Service
public class ChatPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChatPostProcessor.class);

    /** 窗口外攒够这么多条未摘要消息才触发一次摘要(降低长会话摘要频率) */
    private static final int SUMMARY_BATCH = 5;
    /** 摘要文本超过这个长度(字符)就整体重压一次, 防止无限膨胀 */
    private static final int SUMMARY_MAX_CHARS = 2000;
    /** 落库的乐观锁重试次数 */
    private static final int MAX_RETRY = 3;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChatClient chatClient;
    private final ChatModelLoggingAdvisor loggingAdvisor;
    private final TransactionTemplate transactionTemplate;

    public ChatPostProcessor(ConversationRepository conversationRepository,
                             MessageRepository messageRepository,
                             ChatClient.Builder chatClientBuilder,
                             ChatModelLoggingAdvisor loggingAdvisor,
                             PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chatClient = chatClientBuilder.defaultAdvisors(loggingAdvisor).build();
        this.loggingAdvisor = loggingAdvisor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 若窗口外攒够了未摘要的老消息, 调模型压缩进 summary 并推进 summarizedCount.
     * 失败不推进游标, 下次重试.
     */
    @Async("chatExecutor")
    public void summarizeIfNeeded(Long conversationId) {
        try {
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null) {
                return;
            }
            int summarized = conv.getSummarizedCount() == null ? 0 : conv.getSummarizedCount();
            long total = messageRepository.countByConversationId(conversationId);
            int windowSize = ChatMemoryConfig.WINDOW_SIZE;
            if (total <= windowSize) {
                return;  // 全在窗口内
            }
            int outsideCount = (int) (total - windowSize);
            int pending = outsideCount - summarized;
            if (pending < SUMMARY_BATCH) {
                return;  // 攒一攒再摘
            }

            // 取窗口外那批(前 outsideCount 条升序)再切出待摘要段; 只摘 user/assistant
            List<Message> outside = messageRepository.findByConversationIdOrderByCreatedAtAsc(
                    conversationId, PageRequest.of(0, outsideCount));
            List<Message> toSummarize = outside.subList(summarized, outsideCount).stream()
                    .filter(m -> Message.ROLE_USER.equals(m.getRole())
                            || Message.ROLE_ASSISTANT.equals(m.getRole()))
                    .toList();
            if (toSummarize.isEmpty()) {
                // 没有可摘内容(都被滤掉), 仍推进游标避免反复空触发
                persistSummary(conversationId, conv.getSummary(), outsideCount, 0);
                return;
            }

            SummarizeOutcome out = summarizeInto(conv.getSummary(), toSummarize);
            if (!out.ok()) {
                return;  // 摘要失败: 不推进, 下次重试
            }
            persistSummary(conversationId, out.text(), outsideCount, out.tokens());
        } catch (Exception e) {
            // 异步任务异常不冒泡, 只记日志; 游标未推进, 下次 chat 会重试
            log.warn("会话 {} 异步摘要失败, 留待下次重试", conversationId, e);
        }
    }

    /** 首轮(title 为空)时调模型总结生成标题; 失败 title 保持 null, 下次重试 */
    @Async("chatExecutor")
    public void generateTitleIfNeeded(Long conversationId, String userContent, String reply) {
        try {
            Conversation conv = conversationRepository.findById(conversationId).orElse(null);
            if (conv == null) {
                return;
            }
            if (conv.getTitle() != null && !conv.getTitle().isBlank()) {
                return;  // 已有标题
            }
            TitleGen g = generateTitle(userContent, reply);
            persistTitle(conversationId, g.title(), g.tokens());
        } catch (Exception e) {
            log.warn("会话 {} 异步生成标题失败, 留待下次重试", conversationId, e);
        }
    }

    // ---------- 摘要 ----------

    /** 把一批老消息整合进旧摘要; 超长整体重压; 失败 ok=false */
    private SummarizeOutcome summarizeInto(String oldSummary, List<Message> messages) {
        StringBuilder text = new StringBuilder();
        if (oldSummary != null && !oldSummary.isBlank()) {
            text.append("已有摘要：\n").append(oldSummary).append("\n\n新增对话：\n");
        }
        for (Message m : messages) {
            String who = Message.ROLE_USER.equals(m.getRole()) ? "用户" : "助手";
            text.append(who).append("：").append(m.getContent()).append("\n");
        }
        String prompt = "请把以下内容整合成一段简洁的中文摘要（保留关键事实、用户偏好、未完成的事项），"
                + "直接输出摘要文本，不要加多余说明：\n" + text;
        try {
            var callResult = chatClient.prompt().user(prompt).call();
            String s = callResult.content();
            if (s == null || s.isBlank()) {
                return new SummarizeOutcome(null, 0, false);
            }
            String summary = s.trim();
            int tokens = extractTokens(callResult.chatResponse());
            if (summary.length() > SUMMARY_MAX_CHARS) {
                summary = recompress(summary);  // 防膨胀
            }
            return new SummarizeOutcome(summary, tokens, true);
        } catch (Exception e) {
            log.debug("生成摘要失败, 本批下次重试", e);
            return new SummarizeOutcome(null, 0, false);
        }
    }

    /** 摘要过长时整体重压一段; 失败保留原样 */
    private String recompress(String summary) {
        try {
            String prompt = "请把下面这段已有的对话摘要压缩成一段更精炼的中文摘要（保留所有关键事实），"
                    + "直接输出：\n" + summary;
            var callResult = chatClient.prompt().user(prompt).call();
            String s = callResult.content();
            return (s == null || s.isBlank()) ? summary : s.trim();
        } catch (Exception e) {
            return summary;
        }
    }

    // ---------- 标题 ----------

    /** 再调一次模型总结对话生成标题; 失败兜底"新对话" */
    private TitleGen generateTitle(String userContent, String reply) {
        String prompt = "请用不超过20个字的一句话总结下面这段对话的主题，直接输出标题文本，"
                + "不要加引号、序号或多余说明：\n用户：" + userContent + "\n助手：" + reply;
        try {
            var callResult = chatClient.prompt().user(prompt).call();
            int tokens = extractTokens(callResult.chatResponse());
            String title = callResult.content();
            if (title == null || title.isBlank()) {
                return new TitleGen("新对话", tokens);
            }
            title = title.trim().replaceAll("[\"'“”‘’「」]", "");
            return new TitleGen(title.length() > 200 ? title.substring(0, 200) : title, tokens);
        } catch (Exception e) {
            log.debug("生成标题失败, 兜底'新对话'", e);
            return new TitleGen("新对话", 0);
        }
    }

    // ---------- 持久化(乐观锁重试) ----------

    /** 写回 summary + summarizedCount + 累加摘要 token; 仅当新游标更大才写(不倒退) */
    private void persistSummary(Long conversationId, String summary, int summarizedCount, int tokens) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Conversation conv = conversationRepository.findById(conversationId).orElse(null);
                    if (conv == null) {
                        return;
                    }
                    int current = conv.getSummarizedCount() == null ? 0 : conv.getSummarizedCount();
                    if (summarizedCount <= current) {
                        return;  // 已被别的请求推进到更高, 不倒退
                    }
                    conv.setSummary(summary);
                    conv.setSummarizedCount(summarizedCount);
                    if (tokens > 0) {
                        int prev = conv.getTotalTokens() == null ? 0 : conv.getTotalTokens();
                        conv.setTotalTokens(prev + tokens);
                    }
                    conversationRepository.save(conv);  // @Version 乐观锁
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                log.debug("会话 {} 写摘要乐观锁冲突, 第 {} 次重试", conversationId, attempt);
            }
        }
        log.warn("会话 {} 写摘要重试耗尽, 留待下次", conversationId);
    }

    /** 写回 title + 累加标题 token; 仅当 title 仍空才写(不覆盖) */
    private void persistTitle(Long conversationId, String title, int tokens) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Conversation conv = conversationRepository.findById(conversationId).orElse(null);
                    if (conv == null) {
                        return;
                    }
                    if (conv.getTitle() != null && !conv.getTitle().isBlank()) {
                        return;  // 已有标题, 不覆盖
                    }
                    conv.setTitle(title);
                    if (tokens > 0) {
                        int prev = conv.getTotalTokens() == null ? 0 : conv.getTotalTokens();
                        conv.setTotalTokens(prev + tokens);
                    }
                    conversationRepository.save(conv);
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                log.debug("会话 {} 写标题乐观锁冲突, 第 {} 次重试", conversationId, attempt);
            }
        }
        log.warn("会话 {} 写标题重试耗尽, 留待下次", conversationId);
    }

    // ---------- 工具 ----------

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

    /** summarizeInto 的产物: 摘要文本 + token; ok=false 表示失败 */
    private record SummarizeOutcome(String text, int tokens, boolean ok) {
    }

    /** generateTitle 的产物: 标题 + token */
    private record TitleGen(String title, int tokens) {
    }
}
