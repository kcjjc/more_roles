package org.example.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.service.loader.ParseResult;
import org.example.service.splitter.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author ckj
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChunkService {
    private final SlidingWindowChunkSplitter slidingWindowSplitter;
    private final StructureAwareChunkSplitter structureAwareSplitter;

    @Value("${rag.chunk.size:512}")
    private int defaultChunkSize;

    @Value("${rag.chunk.overlap:64}")
    private int defaultOverlap;
    /**
     * 对解析结果进行分块
     */
    public List<ChunkResult> chunk(ParseResult parseResult) {
        ChunkConfig config = ChunkConfig.builder()
                .chunkSize(defaultChunkSize)
                .chunkOverlap(defaultOverlap)
                .build();
        return chunk(parseResult, config);
    }

    private List<ChunkResult> chunk(ParseResult parseResult, ChunkConfig config) {
        if (parseResult == null || !parseResult.isSuccess()) {
            return List.of();
        }
        // 判断是否应该用结构感知分块：文档有明显标题结构
        boolean hasStructure = parseResult.getPages().stream()
                .anyMatch(p -> p.getSectionTitle() != null);
        ChunkSplitter splitter = (hasStructure && config.isStructureAware())
                ? structureAwareSplitter
                : slidingWindowSplitter;

        List<ChunkResult> chunks = splitter.split(parseResult, config);
        if (chunks.isEmpty()) {
            log.warn("[分块] 未获取到分块内容：策略={}", splitter.getClass().getSimpleName());
            return List.of();
        }

        // 过滤掉太短的块（少于 20 字符的碎片没有检索价值）
        chunks = chunks.stream()
                .filter(c -> c.getContent().length() >= 20)
                .toList();

        log.info("[分块] 完成分块：策略={}，共{}块，总字符={}",
                splitter.getClass().getSimpleName(),
                chunks.size(),
                chunks.stream().mapToInt(c -> c.getContent().length()).sum());
        return chunks;
    }
}
