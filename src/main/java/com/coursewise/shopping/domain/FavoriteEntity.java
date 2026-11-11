package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 收藏（SRS §6.8，FR-C-06）。
 *
 * <p>约束：
 * <ul>
 *   <li>同一客户对同一商品只有一条记录（IC-02），由唯一索引 {@code (customer_id, product_id)} 兜底；</li>
 *   <li>收藏数量不设上限（BR-48）；</li>
 *   <li>商品下架后条目<b>保留</b>并标注「已下架」，可点开只读详情，但无加购/下单入口（BR-50 / FR-C-03）。</li>
 * </ul>
 */
@Entity
@Table(name = "favorite", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uk_favorite_customer_product", columnNames = {"customer_id", "product_id"}),
        indexes = @Index(name = "idx_favorite_customer", columnList = "customer_id"))
public class FavoriteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
