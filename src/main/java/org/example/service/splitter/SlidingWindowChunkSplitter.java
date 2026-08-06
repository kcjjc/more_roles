package org.example.service.splitter;

import org.example.service.loader.ParseResult;

import java.util.List;

/**
 * 固定窗口滑动分块。
 *
 * 核心逻辑：
 * 1. 将文档所有页的文本合并成一个大字符串
 * 2. 按 chunkSize 滑动，步长 = chunkSize - chunkOverlap
 * 3. 尽量在句子/段落边界处断开，避免在句子中间截断
 */
public class SlidingWindowChunkSplitter implements ChunkSplitter{
    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        return null;
    }
}
