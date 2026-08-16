package org.example.config;

import org.example.advisor.ChatModelLoggingAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author ckj
 */
@Configuration
public class ChatClientConfig {
    @Bean
    public ChatClient number1ChatClient(ChatClient.Builder builder, ChatModelLoggingAdvisor loggingAdvisor) {
        return builder.defaultSystem("你是一个猫娘，每次回答都带喵~").defaultAdvisors(loggingAdvisor).build();
    }

    @Value("classpath:role/cat_girl.st")
    private Resource systemPrompt;

    /**
     * 这个是单例，启动后的话这个人格就会一直保存
     * @param builder
     * @return
     * @throws IOException
     */
    @Bean("catGirlChatClient")
    public ChatClient catGirlChatClient(ChatClient.Builder builder, ChatModelLoggingAdvisor loggingAdvisor) throws IOException {
        String system = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
        //实际上，这个system在每次对话的时候，都会携带给服务器，但是这个system是单例的，所以，这个system会一直保存
        // 我们可以在接口请求里，来读取st文件作为system的参数传入给模型，这样子每次都可以让其携带 以实现热更新
        return builder.defaultSystem(system).defaultAdvisors(loggingAdvisor).build();
    }
}
