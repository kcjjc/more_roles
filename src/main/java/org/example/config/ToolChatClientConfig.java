package org.example.config;

import org.example.advisor.ChatModelLoggingAdvisor;
import org.example.tools.UserTools;
import org.example.tools.WeatherTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author ckj
 */
@Configuration
public class ToolChatClientConfig {
    // 这才是标准的使用方法
    private final UserTools userTools;
    private final WeatherTools weatherTools;
    private final ChatModelLoggingAdvisor loggingAdvisor;
    public ToolChatClientConfig(UserTools userTools, WeatherTools weatherTools, ChatModelLoggingAdvisor loggingAdvisor) {
        this.userTools = userTools;
        this.weatherTools = weatherTools;
        this.loggingAdvisor = loggingAdvisor;
    }
    @Bean("toolChatClient")
    public ChatClient toolChatClient(ChatClient.Builder builder) {
        return builder.defaultTools(userTools, weatherTools).defaultAdvisors(loggingAdvisor).build();
    }
}
