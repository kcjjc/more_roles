package org.example.service.loader;

import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Markdown(.md)解析器: flexmark 解析成 AST 后收集纯文本(丢掉 # / * / [链接](地址) 等标记),
 * 块级节点(段落/标题/列表项)之间自动补换行, 整体算一页。
 *
 * @author ckj
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "MD";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        String markdown;
        try {
            markdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ParseResult.failure("Markdown 读取失败: " + e.getMessage());
        }
        // Parser 与 visitor 实例内部有状态, 每次调用新建, 不做共享缓存
        Node document = Parser.builder().build().parse(markdown);
        String text = new TextCollectingVisitor().collectAndGetText(document);
        if (text == null || text.isBlank()) {
            return ParseResult.failure("Markdown 未提取到文本: " + fileName);
        }
        return ParseResult.singlePage(text.strip());
    }
}
