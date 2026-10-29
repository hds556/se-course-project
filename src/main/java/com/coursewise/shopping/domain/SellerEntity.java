package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 卖家账号（SRS §4.3）。系统预置且仅预置一个卖家账号。
 *
 * <p>密码只存哈希，任何存储位置不得出现明文（BR-18）。
 * 兼容两种哈希格式：新写入的 Spring Security scrypt 编码，
 * 以及旧 data.json 中遗留的 {@code scrypt:<salt>:<hex>} 格式。
 */
@Entity
@Table(name = "seller",
        uniqueConstraints = @UniqueConstraint(name = "uk_seller_username", columnNames = "username"))
public class SellerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String username;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 关键操作时间戳（BR-19）：改密时间。 */
    @Column(name = "password_changed_at", length = 32)
    private String passwordChangedAt;

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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public void setPasswordChangedAt(String passwordChangedAt) {
        this.passwordChangedAt = passwordChangedAt;
    }
}
