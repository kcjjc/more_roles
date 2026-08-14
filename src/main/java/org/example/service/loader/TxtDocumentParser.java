package org.example.service.loader;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 纯文本(.txt)解析器: UTF-8 读取全文, 整体算一页。
 *
 * @author ckj
 */
@Component
public class TxtDocumentParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "TXT";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        String text;
        try {
            text = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ParseResult.failure("TXT 读取失败: " + e.getMessage());
        }
        // 去 UTF-8 BOM(Windows 记事本常带), 避免不可见字符混进首个分块
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        if (text.isBlank()) {
            return ParseResult.failure("TXT 内容为空: " + fileName);
        }
        return ParseResult.singlePage(text.strip());
    }
}
