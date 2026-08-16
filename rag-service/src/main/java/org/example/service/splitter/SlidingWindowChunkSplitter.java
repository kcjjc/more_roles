package org.example.service.splitter;

import lombok.extern.slf4j.Slf4j;
import org.example.service.loader.ParseResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定窗口滑动分块。
 *
 * 核心逻辑：
 * 1. 将文档所有页的文本合并成一个大字符串
 * 2. 按 chunkSize 滑动，步长 = chunkSize - chunkOverlap
 * 3. 尽量在句子/段落边界处断开，避免在句子中间截断
 */
@Component("slidingWindowChunkSplitter")
@Slf4j
public class SlidingWindowChunkSplitter implements ChunkSplitter{
    @Override
    public List<ChunkResult> split(ParseResult parseResult, ChunkConfig config) {
        List<ChunkResult> chunks = new ArrayList<>();
        int chunk = 0;
        for(ParseResult.PageContent page : parseResult.getPages()) {
            String text = page.getText();
            if(text == null || text.isBlank()) continue;
            // 将每页的内容切块
            List<String> pageChunks = splitText(text,config.getChunkSize(),config.getChunkOverlap());
            for (String chunkText : pageChunks) {
                if (chunkText.isBlank()) continue;
                chunks.add(ChunkResult.builder()
                        .chunkIndex(chunk++)
                        .content(chunkText)
                        .pageNum(page.getPageNum())
                        .sectionTitle(page.getSectionTitle())
                        .estimatedTokens(estimatedTokens(chunkText))
                        .build());
            }
        }
        return chunks;
    }

    private int estimatedTokens(String chunkText) {
        if (chunkText == null) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for( char c : chunkText.toCharArray()) {
            if (c >= '\u4e00' && c <= '\u9fff') {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) (chineseChars * 1.5 + otherChars * 0.3);
    }

    private List<String> splitText(String text, int chunkSize, int chunkOverlap) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while(start <text.length()) {
            int end = Math.min(start + chunkSize,text.length());
            if (end < text.length()) {
                end = findGoodBreakPoint(text, end);
            }
            String chunk = text.substring(start,end).strip();
            if(!chunk.isBlank()) {
                result.add(chunk);
            }
            int nextStart = end - chunkOverlap;
            if(nextStart <= start) {
                nextStart = end;
            }
            start = nextStart;
        }
        return result;
    }

    private int findGoodBreakPoint(String text, int position) {
        // 最多往回找 100 字，但不得超过 position（前面只有这么多字），
        // 否则当 position 较小时会算出负的 searchRange。min(100, position) 保证它 ∈ [0, 100]。
        int searchRange = Math.min(100, position);
        String[] breakChars = {"\n\n", "\n", "。", "！", "？", "；", "，", " "};
        for (String breakChar : breakChars) {
            // 在 [0, position-1] 内找最近的分隔符（不含 position 本身，它属于下一块）
            int idx = text.lastIndexOf(breakChar, position - 1);
            if (idx > 0 && idx >= position - searchRange) {
                // 断点定在分隔符之后，但封顶不超过原计划切点，避免块超出 chunkSize
                return Math.min(idx + breakChar.length(), position);
            }
        }
        return position;
    }
}
