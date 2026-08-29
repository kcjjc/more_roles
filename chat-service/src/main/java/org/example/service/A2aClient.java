package org.example.service;

import org.example.common.a2a.A2aSendMessageRequest;
import org.example.common.a2a.A2aSendMessageResponse;
import org.example.common.a2a.A2aMessage;
import org.example.common.a2a.A2aTextPart;
import org.example.common.a2a.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A2A(协议 v1.0, HTTP+JSON 绑定)客户端: 发现远程 agent 并向其发消息.
 * <p>
 * 与 {@link RagRetrievalClient}(私有 /internal 接口)平行的标准协议通道:
 * <ul>
 *   <li>{@link #fetchCard()} —— 拉 {@code /.well-known/agent-card.json}(公开端点), 结果缓存 1h
 *       (对齐服务端 Cache-Control; 缓存主要用于观测/校验, send 端点是配置直连的);</li>
 *   <li>{@link #sendMessage(String, Long)} —— POST {@code /message:send}, 请求头带
 *       {@code X-Api-Key} 与 {@code A2A-Version: 1.0}(规范 3.2.5/11.2), kbId 走
 *       {@code message.metadata}(规范 3.2.5 的自由 KV 通道)。</li>
 * </ul>
 * 失败语义: 抛异常由调用方({@link org.example.tools.A2aAgentTool})转降级文案,
 * 与"聊天永不因知识库 agent 挂了而中断"的总则一致。
 *
 * @author ckj
 */
@Service
public class A2aClient {

    private static final Logger log = LoggerFactory.getLogger(A2aClient.class);

    private static final MediaType A2A_JSON = MediaType.parseMediaType("application/a2a+json");

    /** Card 缓存有效期(对齐服务端 Cache-Control: max-age=3600) */
    private static final long CARD_TTL_MS = 3_600_000L;

    private final RestClient restClient;

    private final AtomicReference<CachedCard> cardCache = new AtomicReference<>();

    /** A2A 操作端点的 API key(rag 侧 a2a.api-key 同值部署) */
    @Value("${rag.a2a.api-key:}")
    private String apiKey;

    public A2aClient(@Value("${rag.a2a.base-url:http://localhost:8082}") String baseUrl) {
        // 读超时放大到 30s: message:send 是同步阻塞到终态的, 远端含一次 LLM 调用
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(30_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /** 拉取远程 agent 的 Agent Card(带 1h 内存缓存); 失败抛异常由调用方降级 */
    public AgentCard fetchCard() {
        CachedCard cached = cardCache.get();
        if (cached != null && Instant.now().toEpochMilli() - cached.fetchedAtMs < CARD_TTL_MS) {
            return cached.card;
        }
        AgentCard card = restClient.get()
                .uri("/.well-known/agent-card.json")
                .retrieve()
                .body(AgentCard.class);
        if (card == null) {
            throw new IllegalStateException("Agent Card 为空");
        }
        cardCache.set(new CachedCard(card, Instant.now().toEpochMilli()));
        log.info("[a2a-client] 拉到 Agent Card: name={}, skills={}, url={}",
                card.name(),
                card.skills() == null ? List.of() : card.skills().stream().map(s -> s.id()).toList(),
                card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()
                        ? "?" : card.supportedInterfaces().get(0).url());
        return card;
    }

    /**
     * 发送一条消息并同步等待终态 Task.
     *
     * @param query 完整检索句(模型生成)
     * @param kbId  会话绑定的知识库 id; null = 远端全局检索
     */
    public A2aSendMessageResponse sendMessage(String query, Long kbId) {
        A2aMessage message = new A2aMessage(
                A2aMessage.ROLE_USER,
                List.of(new A2aTextPart(query)),
                UUID.randomUUID().toString(),
                null, null,
                kbId == null ? null : Map.of("kbId", kbId));
        return restClient.post()
                .uri("/message:send")
                .contentType(A2A_JSON)
                .header("X-Api-Key", apiKey)
                .header("A2A-Version", "1.0")
                .body(new A2aSendMessageRequest(message, null))
                .retrieve()
                .body(A2aSendMessageResponse.class);
    }

    /** Card + 拉取时间戳 */
    private record CachedCard(AgentCard card, long fetchedAtMs) {
    }
}
