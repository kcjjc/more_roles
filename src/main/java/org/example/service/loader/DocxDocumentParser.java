package org.example.service.loader;

import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Word(.docx, POI)解析器: 提取正文与表格文本(含标题样式的段落文字, 不保留样式),
 * 整体算一页。旧版 .doc 二进制格式不支持(会解析失败并返回原因)。
 *
 * @author ckj
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "DOCX";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        String text;
        try (XWPFDocument doc = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            text = extractor.getText();
        } catch (Exception e) {
            return ParseResult.failure("DOCX 解析失败: " + e.getMessage());
        }
        if (text == null || text.isBlank()) {
            return ParseResult.failure("DOCX 未提取到文本: " + fileName);
        }
        return ParseResult.singlePage(text.strip());
    }
}
