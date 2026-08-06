package org.example.service.splitter;

import lombok.extern.slf4j.Slf4j;
import org.example.service.loader.ParseResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 结构感知分块：优先在标题/段落边界处断开。
 *
 * 适用场景：结构清晰的文档（技术规范、手册等）。
 * 对于流水文字（新闻、小说）效果不如固定窗口。
 *
 * 核心思路：
 * 1. 按标题行切分为若干"节"
 * 2. 节太大则再用固定窗口切分
 * 3. 节太小则与下一节合并（避免碎片化）
 */
@Component("structureAwareSplitter")
@Slf4j
public class StructureAwareChunkSplitter implements ChunkSplitter {
    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        return null;
    }
}
