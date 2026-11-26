package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 客户账号（需求分析 V3.5 §5.3，FR-C-04）。
 *
 * <p>字段与约束：
 * <ul>
 *   <li>用户名：唯一，登录凭证（BR-14）；允许与卖家用户名相同（T-35，两套独立账号体系）。</li>
 *   <li>密码：只存哈希，任何位置不出现明文；不少于 8 位（T-9 只校验长度）。</li>
 *   <li>电话：必填但<b>不唯一</b>，一个手机号可注册多个账号（C-8）；不作登录凭证。</li>
 *   <li>默认交易地点：选填，自由文本，最多 200 字符（T-25）。</li>
 *   <li>注册时间：系统记录，客户列表排序依据。</li>
 * </ul>
 *
 * <p>客户注册后不能修改资料、不能注销账号（FR-C-08 / BR-15），故实体只提供读取。
 */
@Entity
@Table(name = "customer")
public class CustomerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户名：唯一，登录凭证（BR-14）。唯一性由服务层校验 + 列级 unique 兜底。 */
    @Column(nullable = false, length = 64, unique = true)
    private String username;

    /** 密码哈希（Spring Security scrypt），不存明文。 */
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    /** 电话：必填、不唯一，仅用于交易联系（FR-C-04）。 */
    @Column(nullable = false, length = 32)
    private String phone;

    /** 默认交易地点：选填，自由文本 ≤ 200 字符（T-25）。 */
    @Column(name = "default_place", length = 200)
    private String defaultPlace;

    /** 注册时间（ISO-8601 字符串，系统记录）。 */
    @Column(name = "registered_at", nullable = false, length = 32)
    private String registeredAt;

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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getDefaultPlace() {
        return defaultPlace;
    }

    public void setDefaultPlace(String defaultPlace) {
        this.defaultPlace = defaultPlace;
    }

    public String getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(String registeredAt) {
        this.registeredAt = registeredAt;
    }
}
