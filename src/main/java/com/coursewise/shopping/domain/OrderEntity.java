package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 订单（SRS §6.5，BR-10 / BR-19）。
 *
 * <p>一笔订单由一次提交产生，含一笔或多笔 {@link OrderItemEntity} 明细；状态
 * <b>整单流转</b>，不逐商品流转（BR-10）。字段严格按 SRS §6.5 定义——只有
 * 订单号、客户、总金额、状态与下单时间，<b>只有下单时间一个时间戳、无地址字段</b>（BR-19）。
 *
 * <p>「已取消」是独立状态，不记录取消时间与原因、不区分取消方（BR-14 / BR-16）。
 * 订单与明细均不可物理删除（IC-06）。
 */
@Entity
@Table(name = "orders", indexes = {
        // 「我的订单」按 (customer_id) 过滤 + 下单时间倒序分页
        @Index(name = "idx_orders_customer_created", columnList = "customer_id, created_at"),
        @Index(name = "idx_orders_status", columnList = "status")
})
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /** 总金额 = Σ(明细单价 × 数量)，服务端计算（BR-24），单位「分」。 */
    @Column(name = "total_cents", nullable = false)
    private long totalCents;

    @Column(nullable = false, length = 16)
    private OrderStatus status;

    /** 下单时间：ISO 8601，订单唯一时间戳（BR-19）。 */
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

    public long getTotalCents() {
        return totalCents;
    }

    public void setTotalCents(long totalCents) {
        this.totalCents = totalCents;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }
}
