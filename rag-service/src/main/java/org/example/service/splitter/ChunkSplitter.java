package org.example.service.splitter;

import org.example.service.loader.ParseResult;

import java.util.List;

/**
 * @author ckj
 */
public interface ChunkSplitter {
    /**
     * 将解析结果拆分为若干块。
     *
     * @param parseResult 文档解析结果（含多页内容）
     * @param config      分块参数
     * @return 分块列表
     */
    List<ChunkResult> split(ParseResult parseResult, ChunkConfig config);
}
