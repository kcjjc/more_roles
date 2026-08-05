package org.example.controller;

import cn.dev33.satoken.stp.StpUtil;
import org.example.common.Result;
import org.example.entity.User;
import org.example.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 注册 / 登录 / 注销接口.
 * <p>
 * 登录成功后, 返回 Sa-Token 的 tokenValue; 前端后续请求把它放在请求头 {@code satoken: xxx} 里即可.
 *
 * @author ckj
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 与 application.yaml 里 sa-token.token-name 保持一致 */
    private static final String TOKEN_NAME = "satoken";

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    /** 注册请求体 */
    public record RegisterRequest(String username, String password) {
    }

    /** 登录请求体 */
    public record LoginRequest(String username, String password) {
    }

    /**
     * 注册: POST /api/auth/register (注册成功自动登录, 直接返回 token)
     * body: {"username":"alice","password":"123456"}
     */
    @PostMapping("/register")
    public Result<?> register(@RequestBody RegisterRequest req) {
        try {
            User user = userService.register(req.username(), req.password());
            return Result.ok(Map.of(
                    "userId", user.getId(),
                    "username", user.getUsername(),
                    "tokenName", TOKEN_NAME,
                    "tokenValue", StpUtil.getTokenValue()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 登录: POST /api/auth/login
     * body: {"username":"alice","password":"123456"}
     */
    @PostMapping("/login")
    public Result<?> login(@RequestBody LoginRequest req) {
        try {
            User user = userService.login(req.username(), req.password());
            return Result.ok(Map.of(
                    "userId", user.getId(),
                    "username", user.getUsername(),
                    "tokenName", TOKEN_NAME,
                    "tokenValue", StpUtil.getTokenValue()));
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 注销: POST /api/auth/logout (清除当前会话)
     */
    @PostMapping("/logout")
    public Result<?> logout() {
        StpUtil.logout();
        return Result.ok(null);
    }
}
