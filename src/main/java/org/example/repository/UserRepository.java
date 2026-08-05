package org.example.repository;

import org.example.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户数据访问层
 * <p>
 * 继承 JpaRepository 就自动有了 save / findById / findAll / deleteById 等方法, 一行 SQL 不用写。
 *
 * @author ckj
 */
public interface UserRepository extends JpaRepository<User, Long> {

    // 按用户名查询(登录用) —— Spring Data 根据方法名自动生成 SQL:
    // select * from users where username = ?
    Optional<User> findByUsername(String username);
}
