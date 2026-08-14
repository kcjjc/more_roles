package org.example.tools;

import org.example.entity.User;
import org.example.service.UserService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 来查询用户信息的  仅限管理员来使用
 * @author ckj
 */
@Component
public class UserTools {
    @Autowired
    private UserService userService;

    @Tool(description = """
            查询用户的信息
            """)
    public String queryUserInfo(@ToolParam(description = "用户的账户") String userAccount) {
        // 去调用service来查询用户信息
        User byUsername = userService.findByUsername(userAccount);
        if (byUsername == null) {
            return "用户 " + userAccount + " 不存在";
        }
        // 只回显 id / 用户名: 工具返回会进入模型上下文和日志, 密码等敏感字段绝不能出现在这里
        return "用户 " + userAccount + " 的信息：userId=" + byUsername.getId()
                + "，username=" + byUsername.getUsername();
    }
}
