package org.example.service.loader;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * @author ckj
 */
@Data
@Builder
public class ParseResult {
    /**
     * 解析是否成功
     */
    private boolean success;

    /**
     * 错误信息（success=false 时有值）
     */
    private String errorMsg;

    /**
     * 解析出的页面列表（PDF 按页，其他格式整体算一页）
     */
    private List<PageContent> pages;

    /**
     * 文档总页数
     */
    private int totalPages;

    /**
     * 文档标题（如果能识别）
     */
    private String title;

    @Data
    @Builder
    public static class PageContent {
        /**
         * 页码（1-based）
         */
        private int pageNum;
        /**
         * 该页的纯文本内容
         */
        private String text;
        /**
         * 该页识别到的章节标题（可能为空）
         */
        private String sectionTitle;
    }

}
