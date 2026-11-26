package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 订单明细（SRS §6.6，BR-20）。
 *
 * <p>每条明细记录<b>下单快照</b>：商品名称与单价在下单时固化，之后卖家改名或改价
 * 都不影响已有订单（AC-08）。{@code quantity} ≥ 1（IC-05）。
 */
@Entity
@Table(name = "order_item", indexes = {
        @Index(name = "idx_order_item_order", columnList = "order_id"),
        @Index(name = "idx_order_item_product", columnList = "product_id")
})
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** 下单快照：商品名称。 */
    @Column(name = "product_name", nullable = false, length = 128)
    private String productName;

    /** 下单快照：单价（单位「分」）。 */
    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @Column(nullable = false)
    private int quantity;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public long getUnitPriceCents() {
        return unitPriceCents;
    }

    public void setUnitPriceCents(long unitPriceCents) {
        this.unitPriceCents = unitPriceCents;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    /** 本条明细小计 = 单价 × 数量。 */
    public long subtotalCents() {
        return unitPriceCents * quantity;
    }
}
