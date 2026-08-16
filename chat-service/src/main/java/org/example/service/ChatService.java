package org.example.service;

import org.example.advisor.ChatModelLoggingAdvisor;
import org.example.entity.Conversation;
import org.example.entity.Message;
import org.example.repository.ConversationRepository;
import org.example.repository.MessageRepository;
import org.example.common.rag.RetrievalHit;
import org.example.service.RagRouterService.RouteDecision;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.dao.OptimisticLockingFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 对话编排服务: 会话管理 + 人格注入 + 窗口记忆 + RAG 检索 + 持久化.
 * <p>
 * 长会话记忆策略:
 *  * 窗口 —— 由 {@link MessageChatMemoryAdvisor} 自动加载最近 N 条历史注入 prompt.
 *  * 摘要 —— 窗口外的老消息压缩成一段存 conversation.summary, 并入 system 发给模型;
 *    摘要/标题的【生成】已剥离到 {@link ChatPostProcessor} 异步执行, 不阻塞主响应.
 * <p>
 * RAG(会话绑定 kbId 时生效): 消息先过 {@link RagRouterService} 判定是否检索并改写检索句,
 * 需要时经 {@link RagRetrievalClient} 调 rag-service 检索(跨服务), 命中片段并入 system 的【参考资料】段.
 * 命中内容只进当轮 prompt, 不落 message 表.
 * <p>
 * 事务边界: {@link #chat} 主路径只做 读上下文 → (路由 → 检索) → 调主模型 → 落库.
 * 主路径至多两次 LLM 调用: 绑库会话 = 一次小输出路由 + 一次主回复; 未绑库或路由失败退化为一次.
 * 落库走短事务 + {@code @Version} 乐观锁重试. 摘要/标题等无关副作用异步化, 不让用户为之等待.
 *
 * @author ckj
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 落库段乐观锁冲突时的最大重试次数 */
    private static final int MAX_PERSIST_RETRY = 3;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final PersonaService personaService;
    /** RAG 检索客户端: 跨服务调 rag-service(绑库会话按路由决策检索 + 建会话前的知识库归属校验) */
    private final RagRetrievalClient ragRetrievalClient;
    /** RAG 路由器: 判定本轮消息是否需要检索 + 改写检索句 */
    private final RagRouterService ragRouterService;
    /** 后台副作用处理器(摘要 / 标题生成), 异步 */
    private final ChatPostProcessor postProcessor;
    /** 不带 defaultSystem 的 ChatClient, 每次对话动态 .system(persona) 注入人格 */
    private final ChatClient chatClient;
    /** 窗口记忆(底层走 JpaChatMemoryRepository, 取 message 表最近 WINDOW_SIZE 条) */
    private final ChatMemory chatMemory;
    /** 落库段用的短事务模板(chat 本身不在事务里, LLM 调用在事务外) */
    private final TransactionTemplate transactionTemplate;
    /** 模型调用三段式日志(onRequest/onResponse/onError) */
    private final ChatModelLoggingAdvisor loggingAdvisor;

    public ChatService(ConversationRepository conversationRepository,
                       MessageRepository messageRepository,
                       PersonaService personaService,
                       RagRetrievalClient ragRetrievalClient,
                       RagRouterService ragRouterService,
                       ChatPostProcessor postProcessor,
                       ChatClient.Builder chatClientBuilder,
                       ChatMemory chatMemory,
                       ChatModelLoggingAdvisor loggingAdvisor,
                       PlatformTransactionManager transactionManager) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.personaService = personaService;
        this.ragRetrievalClient = ragRetrievalClient;
        this.ragRouterService = ragRouterService;
        this.postProcessor = postProcessor;
        this.chatClient = chatClientBuilder.defaultAdvisors(loggingAdvisor).build();
        this.chatMemory = chatMemory;
        this.loggingAdvisor = loggingAdvisor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 新建会话(校验人格存在且属于该用户; kbId 非空时校验知识库存在且属于该用户) */
    @Transactional
    public Conversation createConversation(Long userId, String personaId, Long kbId) {
        personaService.getContent(userId, personaId)
                .orElseThrow(() -> new IllegalArgumentException("人格不存在或已删除"));
        if (kbId != null) {
            if (!ragRetrievalClient.kbOwned(kbId, userId)) {
                throw new IllegalArgumentException("知识库不存在: kbId=" + kbId);
            }
        }
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setPersonaId(personaId);
        conv.setKbId(kbId);
        return conversationRepository.save(conv);
    }

    /** 列出某用户某人格下的会话(按最后更新时间倒序) */
    public List<Conversation> getConversations(Long userId, String personaId) {
        return conversationRepository.findByUserIdAndPersonaIdOrderByUpdatedAtDesc(userId, personaId);
    }

    /** 会话详情: 会话元信息 + 历史消息(校验归属) */
    public ConversationDetail getConversationDetail(Long userId, Long conversationId) {
        Conversation conv = requireOwned(userId, conversationId);
        List<Message> messages = messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId);
        return new ConversationDetail(conv, messages);
    }

    /**
     * 核心: 发送一条消息并拿到模型回复.
     * <p>
     * 主路径做 读上下文 → (路由 → 检索) → 调主模型 → 落库. 绑库会话多一次小输出路由调用,
     * 其余对【本次回复】无贡献的副作用(摘要/标题)在落库后异步投递给 {@link ChatPostProcessor}.
     */
    public String chat(Long userId, Long conversationId, String userContent) {
        if (userContent == null || userContent.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        Conversation conv = requireOwned(userId, conversationId);
        String convKey = String.valueOf(conversationId);

        // 1. 人格 + 当前摘要(只读, 本轮不生成)
        String persona = resolvePersona(userId, conv);

        // 2. RAG(绑库会话): 路由器判定要不要查 + 改写检索句, 需要才查库.
        //    任何路由失败都退回"拿原话查", 聊天不因路由器中断; 命中片段只进当轮 system, 不落库
        int routerTokens = 0;
        List<RetrievalHit> hits = List.of();
        if (conv.getKbId() != null) {
            RouteDecision rd = ragRouterService.route(
                    resolvePersonaName(userId, conv), chatMemory.get(convKey), userContent);
            log.info("[route] conversationId={} need={} fallback={} query=\"{}\"",
                    conversationId, rd.need(), rd.fallback(), rd.query());
            routerTokens = rd.tokens();
            if (rd.need()) {
                hits = ragRetrievalClient.retrieve(rd.query(), conv.getKbId(), null);   // topK=null → rag 侧默认配置
            }
        }

        // 3. system = 人格 + 摘要 + 参考资料; advisor 自动加载窗口历史注入
        String system = buildSystem(persona, conv.getSummary(), hits);

        // 4. 调主模型(主路径的主 LLM 调用, 事务外)
        ChatResult result = invokeModel(system, convKey, userContent);
        if (result.reply() == null || result.reply().isBlank()) {
            throw new IllegalStateException("模型返回为空");
        }

        // 5. 落库主交互(短事务 + 乐观锁重试): 仅 user/assistant 消息 + token
        //    message 行只记主调用 token, 会话累计含路由消耗
        persistWithRetry(conversationId, userContent, result, routerTokens);

        // 6. 投递后台副作用(异步, 不阻塞): 摘要 / 标题. 主交互事务已提交, 异步任务能看到新消息
        postProcessor.summarizeIfNeeded(conversationId);
        postProcessor.generateTitleIfNeeded(conversationId, userContent, result.reply());

        return result.reply();
    }

    /** 删除会话(连带删除其下所有消息) */
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conv = requireOwned(userId, conversationId);
        messageRepository.deleteByConversationId(conversationId);
        conversationRepository.delete(conv);
    }

    // ---------- chat() 各步骤 ----------

    /** 校验会话存在且属于该用户, 返回会话 */
    private Conversation requireOwned(Long userId, Long conversationId) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
        if (!conv.getUserId().equals(userId)) {
            throw new IllegalArgumentException("无权访问该会话");
        }
        return conv;
    }

    /** 取人格完整内容(底层合并 persona_fragment 的所有分片) */
    private String resolvePersona(Long userId, Conversation conv) {
        return personaService.getContent(userId, conv.getPersonaId())
                .orElseThrow(() -> new IllegalArgumentException("人格不存在或已删除"));
    }

    /** 取人格名称(路由器改写检索句时还原指代用, 如"她"→具体角色名); 查不到给"未知角色" */
    private String resolvePersonaName(Long userId, Conversation conv) {
        return personaService.getPersonaName(userId, conv.getPersonaId())
                .orElse("未知角色");
    }

    /**
     * system = 人格 + 历史摘要(若有) + 参考资料(若有命中); 三段都并入 system, 不单独成条消息.
     * <p>
     * 不变量: 检索命中<b>只进当轮 prompt, 绝不写成 message 落库</b> —— 否则会挤占记忆窗口,
     * 且被异步摘要压缩进 summary, 双重污染长会话记忆. 每轮按当轮检索句重查, 天然跟随话题漂移.
     */
    private String buildSystem(String persona, String summary, List<RetrievalHit> hits) {
        StringBuilder sb = new StringBuilder(persona);
        if (summary != null && !summary.isBlank()) {
            sb.append("\n\n[历史对话摘要，供你保持连贯]\n").append(summary);
        }
        if (hits != null && !hits.isEmpty()) {
            sb.append("\n\n[参考资料]\n")
              .append("以下片段检索自知识库，与用户本轮消息相关。回答涉及事实性内容时优先依据它们；")
              .append("若片段与问题无关，按你原本的角色设定正常回答：\n");
            for (int i = 0; i < hits.size(); i++) {
                RetrievalHit hit = hits.get(i);
                sb.append('[').append(i + 1).append("] ");
                if (hit.pageNum() != null || hit.sectionTitle() != null) {
                    if (hit.pageNum() != null) {
                        sb.append("第").append(hit.pageNum()).append("页");
                    }
                    if (hit.sectionTitle() != null) {
                        if (hit.pageNum() != null) {
                            sb.append("·");
                        }
                        sb.append(hit.sectionTitle());
                    }
                    sb.append('\n');
                }
                sb.append(hit.content()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 调用大模型: MessageChatMemoryAdvisor 自动加载窗口历史注入, conversationId 绑本会话.
     * <p>
     * ⚠️ 只能触发一次 {@code doGetObservableChatClientResponse}: advisor 链的 {@code callAdvisors}
     * 是一次性 Deque, {@code nextCall} 一路 pop, 整条链走完就空了. 故绝不能对同一个 callSpec
     * 同时调 {@code content()} 和 {@code chatResponse()} —— 它俩各自独立触发一次完整链执行,
     * 第二次 {@code nextCall} 会在已空的链上抛 "No CallAdvisors available to execute".
     * 这里取一次 {@code chatResponse()}, 再从同一响应里同时拿回复内容和 token.
     */
    private ChatResult invokeModel(String system, String convKey, String userContent) {
        MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId(convKey)
                .build();
        ChatResponse chatResponse = chatClient.prompt()
                .system(system)
                .advisors(advisor)
                .user(userContent)
                .call()
                .chatResponse();
        String reply = null;
        if (chatResponse != null) {
            reply = chatResponse.getResult().getOutput().getText();
        }
        return new ChatResult(reply, extractTokens(chatResponse));
    }

    /**
     * 落库主交互: 插入 user/assistant 消息, 累加 token. 短事务 + {@code @Version} 乐观锁重试.
     * message 行只记主调用的 token; 会话累计 totalTokens 额外含路由调用的消耗(成本统计完整).
     * 重试耗尽抛 {@link OptimisticLockingFailureException}, 命中全局 handler → "操作冲突，请重试".
     */
    private void persistWithRetry(Long conversationId, String userContent, ChatResult result, int routerTokens) {
        for (int attempt = 1; attempt <= MAX_PERSIST_RETRY; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Conversation conv = conversationRepository.findById(conversationId)
                            .orElseThrow(() -> new IllegalArgumentException("会话不存在"));
                    saveMessage(conversationId, Message.ROLE_USER, userContent, 0);
                    saveMessage(conversationId, Message.ROLE_ASSISTANT, result.reply(), result.tokens());
                    accumulateTokens(conv, result.tokens() + routerTokens);
                    conversationRepository.save(conv);   // @Version 乐观锁; @PreUpdate 刷 updated_at
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                log.debug("会话 {} 落库乐观锁冲突, 第 {} 次重试", conversationId, attempt);
            }
        }
        throw new OptimisticLockingFailureException(
                "会话 " + conversationId + " 并发冲突, 落库失败(已重试 " + MAX_PERSIST_RETRY + " 次)");
    }

    /** 累加 token 到会话 */
    private void accumulateTokens(Conversation conv, int tokens) {
        int prev = conv.getTotalTokens() == null ? 0 : conv.getTotalTokens();
        conv.setTotalTokens(prev + tokens);
    }

    private void saveMessage(Long conversationId, String role, String content, int tokens) {
        Message m = new Message();
        m.setConversationId(conversationId);
        m.setRole(role);
        m.setContent(content);
        m.setTokens(tokens);
        messageRepository.save(m);
    }

    /** 从响应里取本次消耗的 token 数, 取不到兜底 0 */
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

    /** 一次模型调用的结果: 回复内容 + 消耗 token */
    private record ChatResult(String reply, int tokens) {
    }

    /** 会话详情 DTO: 会话元信息 + 历史消息 */
    public record ConversationDetail(Conversation conversation, List<Message> messages) {
    }
}
