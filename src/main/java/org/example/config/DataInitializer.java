package org.example.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.Document;
import org.example.repository.DocumentRepository;
import org.example.service.IndexService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 初始化默认向量库数据
 * @author ckj
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {
    private final DocumentRepository documentRepository;
    private final IndexService indexService;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 假设向量库里没有数据，代表没有存入系统文档，那么需要优先存入系统文档
        if (documentRepository.count() > 0) {
            // 无需初始化了，已有数据
            log.info("[DataInitializer]已有数据存在，无需初始化");
            return;
        }

        log.info("[DataInitializer]开始初始化");

        // 开始存入系统文档
        initDocument(1L,"hr-handbook.txt","hr-handbook.txt","TXT",0L,"docs/hr-handbook.txt");
        initDocument(1L,"product-faq.txt","product-faq.txt","TXT",0L,"docs/product-faq.txt");
        initDocument(1L,"tech-spec.txt","tech-spec.txt","TXT",0L,"docs/tech-spec.txt");
    }

    /**
     *  存入系统文档
     */
    private void initDocument(Long kb_id,String minioPath,String fileName,String fileType,Long uploadedBy,String classPath) {
        ClassPathResource classPathResource = new ClassPathResource(classPath);
        byte[] content = new byte[0];
        try {
            content = classPathResource.getInputStream().readAllBytes();
        } catch (IOException e) {
            log.error("[DataInitializer]存入系统文档出错", e);
        }
        Document document = new Document();
        document.setKbId(kb_id);
        document.setMinioPath(minioPath);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFileSize((long) content.length);
        document.setUploadedBy(uploadedBy);
        Document save = documentRepository.save(document);

        String text = new String(content, StandardCharsets.UTF_8);
        // 提交给任务表来异步解析文档
        log.info("[DataInitializer]提交任务表来异步解析文档");
        indexService.submitTask(save.getId(), text);
    }
}
