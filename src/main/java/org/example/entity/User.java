package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 用户实体, 对应 PG 里的 users 表
 *
 * @author ckj
 */
@Entity
@Table(name = "users")   // 关键: 表名是 users(复数), 必须显式指定, 否则 JPA 默认用类名 user, 会和 PG 保留字冲突

public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)   // 对应 PG 的 BIGSERIAL 自增
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", username='" + username + '\'' +
                ", password='" + password + '\'' +
                '}';
    }
}
