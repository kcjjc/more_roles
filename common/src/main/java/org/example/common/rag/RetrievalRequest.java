package org.example.common.rag;

/**
 * chat-service → rag-service 的检索请求体.
 *
 * @param query 检索句(chat 侧路由器改写后的文本)
 * @param kbId  知识库 id; 传 null 表示不限知识库(全局检索)
 * @param topK  召回条数; 传 null 或 &lt;=0 由 rag 侧用默认配置
 * @author ckj
 */
public record RetrievalRequest(String query, Long kbId, Integer topK) {
}
