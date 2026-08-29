package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * @author ckj
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {
    private final EmbeddingModel embeddingModel;
    private final StringRedisTemplate redisTemplate;

    private static final String CACHE_PREFIX = "emb:v1:";

    @Value("${rag.cache.embedding-ttl:7d}")
    private Duration embeddingTtl;

    /**
     * 单批条数: DashScope text-embedding-v3 单次批量上限 10(公共/专属实例同), 传 20 会被
     * 400 "batch size should not be larger than 10" 拒绝(存量文档不足 10 块时侥幸不触发)。
     */
    @Value("${rag.embedding.batch-size:10}")
    private int batchSize;

    /**
     * embedding API 调用的重试模板: 3 次尝试, 指数退避 1s / 2s。
     * 用编程式 RetryTemplate 而不是 @Retryable —— embedBatch 里调 embedFromApi 属于同类自调用,
     * 注解式必须经 AOP 代理才生效, 自调用会静默绕过(且工程从未开启 @EnableRetry), 重试从未生效过。
     * NonTransientAiException(400 参数错类)是确定性失败, 重试必然再败 —— 排除, 不空耗退避时间。
     * 注意: builder 的 retryOn / notRetryOn 互斥(同用直接抛 IllegalArgumentException), 未分类的
     * 异常默认就重试, 单用 notRetryOn 即"除 NonTransientAiException 外都重试"。
     */
    private final RetryTemplate retryTemplate = RetryTemplate.builder()
            .maxAttempts(3)
            .exponentialBackoff(1000, 2, 10_000)
            .notRetryOn(org.springframework.ai.retry.NonTransientAiException.class)
            .build();

    /**
     * 批量向量化，带 Redis 缓存。
     * 先查缓存，缓存未命中的批量调 API，结果写入缓存。
     *
     * @param texts 待向量化的文本列表
     * @return 与输入顺序对应的向量列表
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();

        Map<Integer, float[]> cached = new HashMap<>();
        List<Integer> missedIndices = new ArrayList<>();
        List<String> missedTexts = new ArrayList<>();

        for (int i = 0; i < texts.size(); i++) {
            String cacheKey = buildCacheKey(texts.get(i));
            String cachedStr = redisTemplate.opsForValue().get(cacheKey);
            if (cachedStr != null) {
                cached.put(i, deserializeVector(cachedStr));
            } else {
                missedIndices.add(i);
                missedTexts.add(texts.get(i));
            }
        }

        log.debug("[Embedding] 总数={}，缓存命中={}，需要调API={}",
                texts.size(), cached.size(), missedTexts.size());

        if (!missedTexts.isEmpty()) {
            List<float[]> newVectors = embedFromApi(missedTexts);

            for (int j = 0; j < missedIndices.size(); j++) {
                int originalIndex = missedIndices.get(j);
                float[] vector = newVectors.get(j);
                cached.put(originalIndex, vector);

                String cacheKey = buildCacheKey(texts.get(originalIndex));
                redisTemplate.opsForValue().set(cacheKey, serializeVector(vector), embeddingTtl);
            }
        }

        return IntStream.range(0, texts.size())
                .mapToObj(cached::get)
                .toList();
    }

    /**
     * 调 Embedding API，按批次处理，避免单次请求过大。
     * 带重试：网络抖动时自动重试 3 次（RetryTemplate，指数退避）。
     * texts是一个文档的所有分块
     */
    private List<float[]> embedFromApi(List<String> texts) {
        try {
            return retryTemplate.execute(ctx -> doEmbedFromApi(texts));
        } catch (Exception e) {
            log.error("[Embedding] 重试3次后仍失败，texts.size={}，error={}",
                    texts.size(), e.getMessage());
            throw new RuntimeException("Embedding API 调用失败，已重试3次：" + e.getMessage(), e);
        }
    }

    /** 真正调 API 的部分, 由 {@link #embedFromApi} 包住重试执行 */
    private List<float[]> doEmbedFromApi(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        AtomicInteger totalTokens = new AtomicInteger(0);

        // 分批提交
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            List<String> batch = texts.subList(start, end);

            long batchStart = System.currentTimeMillis();
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(batch, null));
            long elapsed = System.currentTimeMillis() - batchStart;

            // 统计 Token 消耗（用于成本监控）
            response.getMetadata().getUsage();
            long tokens = response.getMetadata().getUsage().getTotalTokens();
            totalTokens.addAndGet((int) tokens);

            // 按顺序提取向量
            // Spring AI 1.1.x：Embedding.getOutput() 返回 float[]
            response.getResults().stream()
                    .sorted(Comparator.comparingInt(Embedding::getIndex))
                    .forEach(r -> result.add(r.getOutput()));

            log.debug("[Embedding] 批次{}/{}，size={}，耗时={}ms",
                    start / batchSize + 1,
                    (texts.size() + batchSize - 1) / batchSize,
                    batch.size(), elapsed);
        }

        log.info("[Embedding] API调用完成，共{}条，消耗Token={}",
                texts.size(), totalTokens.get());

        return result;
    }

    /** 单条向量化（查询时使用） */
    public float[] embed(String text) {
        List<float[]> result = embedBatch(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0);
    }

    private String buildCacheKey(String text) {
        // 用内容的 MD5 作为缓存 Key，避免 Key 过长
        return CACHE_PREFIX + toMd5(text);
    }

    private String toMd5(String text) {
        try {
            var md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }

    /**
     * float[] 转逗号分隔字符串存入 Redis。
     * 不用 JSON 序列化器，避免 GenericJackson2JsonRedisSerializer
     * 把浮点数当类名解析导致反序列化失败。
     */
    private String serializeVector(float[] vector) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private float[] deserializeVector(String str) {
        str = str.replace("[", "").replace("]", "").replace(" ", "");
        String[] parts = str.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
