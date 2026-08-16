package org.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * rag-service 启动类: 知识库管理 / 文档上传 / 解析分块 / 向量化索引 / 检索.
 *
 * @author ckj
 */
@SpringBootApplication
public class RagApplication {
    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
