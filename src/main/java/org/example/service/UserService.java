package org.example.service;

import cn.dev33.satoken.stp.StpUtil;
import org.example.entity.User;
import org.example.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author ckj
 */
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;


    public User findByUsername(String username) {
        return userRepository.findByUsername(username).orElse(null);
    }

    /**
     * 注册: 用户名不能重复, 注册成功后【自动登录】(建立 Sa-Token 会话).
     * <p>
     * ⚠️ 安全提示: 密码这里直接明文入库, 仅用于本地演示.
     * 生产环境务必改用哈希, 例如引入 spring-security-crypto 后:
     * <pre>
     *   PasswordEncoder encoder = new BCryptPasswordEncoder();
     *   user.setPassword(encoder.encode(rawPassword));            // 注册: 存哈希
     *   encoder.matches(rawPassword, user.getPassword())          // 登录: 比对哈希
     * </pre>
     *
     * @return 新建的用户(含生成的 id)
     */
    @Transactional
    public User register(String username, String password) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);   // TODO 生产环境换 BCrypt 哈希, 见类注释
        User saved = userRepository.save(user);
        StpUtil.login(saved.getId());   // 注册即登录, 建立 Sa-Token 会话
        return saved;
    }

    /**
     * 登录: 校验用户名 + 密码, 成功后建立 Sa-Token 会话.
     * <p>
     * ⚠️ 这里是明文比对, 仅用于演示; 生产应使用 BCrypt 的 matches().
     * 校验失败统一抛"用户名或密码错误", 不区分用户名是否存在(防枚举).
     *
     * @return 登录成功的用户
     */
    public User login(String username, String password) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (!password.equals(user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        StpUtil.login(user.getId());   // 登录成功, Sa-Token 生成 token 并写入会话
        return user;
    }
}
