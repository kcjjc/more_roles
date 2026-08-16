package org.example.config;

import org.example.repository.JpaChatMemoryRepository;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatMemory 配置: 用 {@link MessageWindowChatMemory} 做窗口,
 * repository 接我们的 {@link JpaChatMemoryRepository}(message 表的窗口视图).
 *
 * @author ckj
 */
@Configuration
public class ChatMemoryConfig {

    /** 窗口大小: 喂给模型的最大消息条数, 与 JpaChatMemoryRepository 的 findTop20 对齐 */
    public static final int WINDOW_SIZE = JpaChatMemoryRepository.WINDOW_SIZE;

    @Bean
    public ChatMemory chatMemory(JpaChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(WINDOW_SIZE)
                .build();
    }
}
