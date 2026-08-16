package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 检索服务: query 文本 → 向量化 → 在 doc_chunk 上做 cosine 相似度检索.
 * <p>
 * 为什么走原生 SQL 而不是 JPA: 向量相似度依赖 pgvector 的 {@code <=>} 操作符(cosine 距离),
 * JPQL/HQL 写不出这个操作符, 只能用 {@link NamedParameterJdbcTemplate} 发原生 SQL.
 * 这也是 {@link org.example.repository.DocChunkRepository} 只管元数据、不承担检索的原因.
 *
 * @author ckj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /** 默认召回条数 */
    @Value("${rag.search.top-k:5}")
    private int defaultTopK;

    /** 最小相似度阈值(0~1, 越大越严); 默认 0 = 不过滤, 让调用方按需开启 */
    @Value("${rag.search.min-score:0.0}")
    private double minScore;

    /**
     * 检索与 query 最相关的分块.
     *
     * @param query 用户问题原文
     * @param kbId  知识库 id; 传 null 表示不限知识库(全局检索)
     * @param topK  召回条数; 传 null 或 <=0 用默认值
     * @return 命中分块(按相似度从高到低), 已过滤掉低于阈值的
     */
    public List<ChunkHit> search(String query, Long kbId, Integer topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int limit = (topK == null || topK <= 0) ? defaultTopK : topK;

        // 1. query 现场向量化(文档侧向量入库时已算好存表里了, 这里只算 query 这一次)
        float[] vec = embeddingService.embed(query);
        if (vec.length == 0) {
            log.warn("[检索] query 向量化返回空, query={}", query);
            return List.of();
        }
        // float[] → pgvector 文本表示 '[0.1,0.2,...]'; SQL 里再 ::vector cast 成真正的 vector 类型
        String vectorLiteral = toPgVector(vec);

        // 2. 原生 SQL 检索.
        //    embedding <=> q 是 cosine 【距离】, 0=完全相同, 所以 ORDER BY 升序 = 最相似的在前.
        //    对外的 score 用 1 - 距离 换算成【相似度】(越大越相关), 人看更直觉.
        //    (:q)::vector 这个 cast 必不可少: 绑定参数进来是 text,
        //    而 <=> 要求两侧都是 vector 类型, 不 cast 会报类型不匹配.
        StringBuilder sql = new StringBuilder("""
                SELECT id, doc_id, kb_id, chunk_index, content,
                       page_num, section_title,
                       1 - (embedding <=> (:q)::vector) AS score
                FROM doc_chunk
                """);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("q", vectorLiteral)
                .addValue("limit", limit);

        // kbId 为空就不加过滤(全局检索); 传了就按知识库隔离
        if (kbId != null) {
            sql.append(" WHERE kb_id = :kbId");
            params.addValue("kbId", kbId);
        }
        sql.append(" ORDER BY embedding <=> (:q)::vector LIMIT :limit");

        List<ChunkHit> hits = jdbcTemplate.query(sql.toString(), params, (rs, i) -> new ChunkHit(
                rs.getLong("id"),
                rs.getLong("doc_id"),
                rs.getLong("kb_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                (Integer) rs.getObject("page_num"),
                rs.getString("section_title"),
                round(rs.getDouble("score"))
        ));

        // 可选: 过滤掉相似度太低的(八竿子打着的才返回)
        if (minScore > 0) {
            hits = hits.stream().filter(h -> h.score() >= minScore).toList();
        }

        log.info("[检索] query=\"{}\" kbId={} topK={} 命中={}", query, kbId, limit, hits.size());
        return hits;
    }

    /** float[] → pgvector 文本表示 '[v1,v2,...]' (与 EmbeddingService 的缓存序列化不同: 这里要带方括号) */
    private String toPgVector(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8).append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    /** 保留 3 位小数, 分数好看一点 */
    private double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** 检索命中的分块 + 相似度分数 */
    public record ChunkHit(Long id, Long docId, Long kbId, Integer chunkIndex,
                           String content, Integer pageNum, String sectionTitle,
                           double score) {
    }
}
