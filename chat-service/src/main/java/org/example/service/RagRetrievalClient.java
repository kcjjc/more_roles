package org.example.service;

import org.example.common.Result;
import org.example.common.rag.RetrievalHit;
import org.example.common.rag.RetrievalRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * rag-service 的 HTTP 客户端(手写 RestClient, 不引 Feign —— 轻量方案的核心调用点).
 * <p>
 * 两个内部接口:
 * <ul>
 *   <li>POST /internal/retrieval —— 绑库会话按路由决策检索</li>
 *   <li>GET  /internal/kb/{kbId}/owned —— 新建绑库会话前的归属校验</li>
 * </ul>
 * 失败语义 deliberately 不同:
 * 检索失败<b>降级</b>(返回空列表跳过 RAG, 聊天不能因为知识库挂了而中断);
 * 归属校验失败<b>上抛</b>(用户显式要求绑定, 绑不上就该明确报错, 不能静默建出假绑定会话).
 *
 * @author ckj
 */
@Service
public class RagRetrievalClient {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalClient.class);

    private final RestClient restClient;

    public RagRetrievalClient(@Value("${rag.service.base-url:http://localhost:8082}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(5_000);
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * 检索与 query 最相关的分块.
     *
     * @param query 检索句(路由器改写后)
     * @param kbId  知识库 id; null = 全局检索
     * @param topK  召回条数; null = rag 侧默认配置
     * @return 命中分块; rag 不可用/响应异常时返回空列表(降级, 跳过 RAG 继续回答)
     */
    public List<RetrievalHit> retrieve(String query, Long kbId, Integer topK) {
        try {
            Result<List<RetrievalHit>> resp = restClient.post()
                    .uri("/internal/retrieval")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new RetrievalRequest(query, kbId, topK))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (resp != null && resp.getCode() == 200 && resp.getData() != null) {
                return resp.getData();
            }
            log.warn("[RAG检索] rag-service 返回异常信封: {}", resp == null ? "null" : resp.getMessage());
            return List.of();
        } catch (Exception e) {
            log.warn("[RAG检索] rag-service 调用失败, 本轮降级跳过 RAG: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 知识库归属校验: kb 存在且属于该用户才返回 true.
     * rag 不可用时抛 {@link IllegalArgumentException}(全局 handler 透传 message,
     * 用户显式要求绑定时必须明确报错, 不能静默建出假绑定会话).
     */
    public boolean kbOwned(Long kbId, Long userId) {
        try {
            Result<Boolean> resp = restClient.get()
                    .uri("/internal/kb/{kbId}/owned?userId={userId}", kbId, userId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return resp != null && resp.getCode() == 200 && Boolean.TRUE.equals(resp.getData());
        } catch (Exception e) {
            log.warn("[RAG归属校验] rag-service 调用失败: {}", e.getMessage());
            throw new IllegalArgumentException("知识库服务暂时无响应，请稍后重试");
        }
    }
}
