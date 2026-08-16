package org.example.service.loader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF(pdfbox 3.x)解析器: 逐页提取文本, 每页一个 {@link ParseResult.PageContent}。
 * 只读文字层, 扫描件(图片型 PDF)提不出文本, 会返回失败并注明原因。
 *
 * @author ckj
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public String supportedType() {
        return "PDF";
    }

    @Override
    public ParseResult parse(InputStream inputStream, String fileName) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            try (PDDocument doc = Loader.loadPDF(bytes)) {
                int pageCount = doc.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                List<ParseResult.PageContent> pages = new ArrayList<>(pageCount);
                for (int i = 1; i <= pageCount; i++) {
                    stripper.setStartPage(i);
                    stripper.setEndPage(i);
                    String text = stripper.getText(doc);
                    if (text != null && !text.isBlank()) {
                        pages.add(ParseResult.PageContent.builder()
                                .pageNum(i)
                                .text(text.strip())
                                .build());
                    }
                }
                if (pages.isEmpty()) {
                    return ParseResult.failure("PDF 未提取到文本(可能是无文字层的扫描件): " + fileName);
                }
                return ParseResult.builder()
                        .success(true)
                        .pages(pages)
                        .totalPages(pageCount)
                        .title(doc.getDocumentInformation() != null
                                ? doc.getDocumentInformation().getTitle()
                                : null)
                        .build();
            }
        } catch (Exception e) {
            return ParseResult.failure("PDF 解析失败: " + e.getMessage());
        }
    }
}
