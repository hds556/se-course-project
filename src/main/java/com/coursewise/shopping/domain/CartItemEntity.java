package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 购物车项（SRS §6.7，FR-C-07）。
 *
 * <p>约束：
 * <ul>
 *   <li>同一客户对同一商品只有一行（IC-02），由唯一索引 {@code (customer_id, product_id)} 兜底；</li>
 *   <li>加购<b>不占用、不锁定库存</b>（BR-02），本表只有数量，与 {@code product.stock} 无关；</li>
 *   <li>条目数与单条数量不设上限（BR-48）；</li>
 *   <li>商品下架后条目<b>保留</b>、由前端置灰标注不可下单，后端不自动删除（BR-49）。</li>
 * </ul>
 */
@Entity
@Table(name = "cart_item", uniqueConstraints = @jakarta.persistence.UniqueConstraint(
        name = "uk_cart_customer_product", columnNames = {"customer_id", "product_id"}),
        indexes = @Index(name = "idx_cart_customer", columnList = "customer_id"))
public class CartItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }
}
