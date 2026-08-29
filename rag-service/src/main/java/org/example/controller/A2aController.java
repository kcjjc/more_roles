package org.example.controller;

import org.example.common.a2a.A2aAgentInterface;
import org.example.common.a2a.A2aSecurityScheme;
import org.example.common.a2a.A2aSendMessageRequest;
import org.example.common.a2a.A2aSendMessageResponse;
import org.example.common.a2a.A2aTask;
import org.example.common.a2a.AgentCapabilities;
import org.example.common.a2a.AgentCard;
import org.example.common.a2a.AgentSkill;
import org.example.service.A2aService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A2A Server 端点(协议 v1.0, HTTP+JSON/REST 绑定).
 * <p>
 * 三个端点(路径由规范固定, 不走 /api 前缀, 网关不路由 —— 与 /internal 同为仅内网可达,
 * 但语义不同: /internal 是私有接口, 这里是<b>标准协议</b>端点, 任何 A2A 客户端都能对接):
 * <ul>
 *   <li>{@code GET  /.well-known/agent-card.json} —— Agent Card(公开, 规范 8.2 发现机制)</li>
 *   <li>{@code POST /message:send} —— 发消息(同步返回终态 Task; 规范 11.3.1)</li>
 *   <li>{@code GET  /tasks/{id}} —— 查任务(404 按 google.rpc.Status 返回 TASK_NOT_FOUND)</li>
 * </ul>
 * 操作端点由 {@link org.example.config.A2aAuthFilter} 做 X-Api-Key 校验.
 * 注意: A2A 错误刻意不走 GlobalExceptionHandler 的 Result 信封 ——
 * 协议端点要说协议的话(规范 11.6 的错误体格式).
 *
 * @author ckj
 */
@RestController
public class A2aController {

    /** A2A 的标准媒体类型(规范 14.1); 注解属性要 String, 响应构造要 MediaType, 两个常量各取所需 */
    private static final String A2A_JSON_VALUE = "application/a2a+json";
    private static final MediaType A2A_JSON = MediaType.parseMediaType(A2A_JSON_VALUE);

    private final A2aService a2aService;

    /** 本服务对外暴露的基址(Card 里的 supportedInterfaces.url 用), 容器内用服务名 */
    @Value("${a2a.base-url:http://localhost:8082}")
    private String baseUrl;

    public A2aController(A2aService a2aService) {
        this.a2aService = a2aService;
    }

    /** Agent Card: agent 的发现入口, 公开不鉴权; 内容低频变化, 带 1h 客户端缓存(规范 8.6) */
    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AgentCard> agentCard() {
        AgentCard card = new AgentCard(
                "knowledge-base-agent",
                "知识库专家 agent: 基于已入库文档(PDF/DOCX/MD/TXT)做检索增强问答，"
                        + "回答附来源片段可核验。不透明协作方 —— 调用方只看到任务结果，不感知内部检索实现。",
                List.of(new A2aAgentInterface(baseUrl, "HTTP+JSON", "1.0")),
                "0.1.0",
                new AgentCapabilities(false, false, null),
                Map.of("apiKey", new A2aSecurityScheme(
                        new A2aSecurityScheme.ApiKeySecurityScheme("HEADER", "X-Api-Key"))),
                List.of(Map.of("apiKey", List.of())),
                List.of("text/plain"),
                List.of("text/plain"),
                List.of(new AgentSkill(
                        "kb-qa", "知识库问答",
                        "对指定知识库回答事实性问题（公司制度、产品手册、角色设定等已入库文档）。"
                                + "闲聊、创作类请求不适用。",
                        List.of("rag", "knowledge-base", "qa", "retrieval"),
                        List.of("年假有几天", "产品支持哪些部署方式"),
                        List.of("text/plain"),
                        List.of("text/plain"))));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(card);
    }

    /** message:send —— 发消息; 同步阻塞至终态(规范 3.2.2 默认行为), 返回 COMPLETED/FAILED 的 Task */
    @PostMapping(value = "/message:send", consumes = A2A_JSON_VALUE, produces = A2A_JSON_VALUE)
    public A2aSendMessageResponse sendMessage(@RequestBody A2aSendMessageRequest request) {
        return a2aService.handleSend(request);
    }

    /** tasks/{id} —— 查任务; 不存在返回规范 11.6 的错误体(404 + TASK_NOT_FOUND) */
    @GetMapping(value = "/tasks/{id}", produces = A2A_JSON_VALUE)
    public ResponseEntity<?> getTask(@PathVariable("id") String taskId) {
        A2aTask task = a2aService.getTask(taskId);
        if (task == null) {
            return ResponseEntity.status(404).contentType(A2A_JSON).body(taskNotFound(taskId));
        }
        return ResponseEntity.ok().contentType(A2A_JSON).body(task);
    }

    /** 规范 11.6: google.rpc.Status JSON + ErrorInfo(reason=TASK_NOT_FOUND, domain=a2a-protocol.org) */
    private Map<String, Object> taskNotFound(String taskId) {
        return Map.of(
                "error", Map.of(
                        "code", 404,
                        "status", "NOT_FOUND",
                        "message", "The specified task ID does not exist or is no longer available",
                        "details", List.of(Map.of(
                                "@type", "type.googleapis.com/google.rpc.ErrorInfo",
                                "reason", "TASK_NOT_FOUND",
                                "domain", "a2a-protocol.org",
                                "metadata", Map.of("taskId", taskId)))));
    }
}
