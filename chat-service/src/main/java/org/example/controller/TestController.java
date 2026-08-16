package org.example.controller;

import org.example.entity.User;
import org.example.repository.UserRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author ckj
 */
@RestController
@RequestMapping("/test")
public class TestController {

    // 同一个类里使用多种配置的chatClient
    private final ChatClient chatClient;
    private final ChatClient maoClient;
    private final ChatClient teacherClient;
    private final UserRepository userRepository;
    private final ChatClient toolChatClient;

    @Value("classpath:role/teacher.st")
    private Resource teacherPrompt;


    public TestController(ChatClient.Builder builder, ChatClient number1ChatClient, UserRepository userRepository, ChatClient toolChatClient) {
        this.chatClient = builder.build();
        this.maoClient = number1ChatClient;
        this.teacherClient = builder.build();
        this.userRepository = userRepository;
        this.toolChatClient = toolChatClient;
    }

    @GetMapping("/hello")
    public String hello() {
        return chatClient.prompt()
                .user("你好")
                .call()
                .content();
    }

    @GetMapping("/mao")
    public String mao() {
        return maoClient.prompt()
                .user("你好")
                .call()
                .content();
    }

    @GetMapping("/teacher")
    public String teacher() throws IOException {
        String system = teacherPrompt.getContentAsString(StandardCharsets.UTF_8);
        return teacherClient.prompt()
                .system(system)
                .user("你是谁")
                .call()
                .content();
    }

    // 验证 JPA 配通: 访问 GET /test/users, 从 PG 查出所有用户名
    @GetMapping("/users")
    public List<String> users() {
        return userRepository.findAll()
                .stream()
                .map(User::getUsername)
                .toList();
    }

    @GetMapping("/getUser")
    public String getUser() {
        return toolChatClient.prompt()
                .user("查询用户名为ckj的用户信息")
                .call()
                .content();
    }


}
