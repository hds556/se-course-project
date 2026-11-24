package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 订单（SRS §6.6，BR-10 / BR-19）。
 *
 * <p>一笔订单由一次提交产生，含一笔或多笔 {@link OrderItemEntity} 明细；状态
 * <b>整单流转</b>，不逐商品流转（BR-10）。字段按 SRS §6.6 定义：订单号、客户、总金额、
 * 状态、下单时间，以及挑战需求C 新增的<b>支付渠道</b>（已支付及之后必填，IC-11）、
 * <b>物流公司 / 物流单号</b>（开始发货及之后必填，IC-12）。
 *
 * <p>「已取消」是独立状态，不记录取消时间与原因、不区分取消方（BR-14 / BR-16）。
 * 订单与明细均不可物理删除（IC-06）。
 *
 * <p>订单表由开发 A 维护；支付与物流四个字段在挑战需求C 迭代由开发 A 新增，
 * 卖家侧（开发 C）推进到「开始发货」时写入物流字段，其余角色只读。
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

    /** 下单时间：ISO 8601，订单唯一时间戳（BR-19）。24h 未支付超时以此时刻起算（S-11）。 */
    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    /**
     * 支付渠道（BR-52 / IC-11）：客户发起支付时选定，「已支付」及之后必有值。
     * 「客户下单」「待支付」「已取消（未付款段）」为 {@code null}。
     */
    @Column(name = "pay_channel", length = 16)
    private PayChannel payChannel;

    /**
     * 发货时间（ISO 8601）：卖家推进到「开始发货」时写入，模拟物流轨迹以此推算
     * （FR-C-12 跟踪信息为模拟数据）。未发货为 {@code null}。
     */
    @Column(name = "shipped_at", length = 32)
    private String shippedAt;

    /** 物流公司（BR-58 选填，落库时统一有值）：开始发货时写入。 */
    @Column(name = "ship_company", length = 16)
    private ShipCompany shipCompany;

    /** 物流单号（BR-58 / IC-12）：开始发货及之后必填，一单一个运单（BR-59）。 */
    @Column(name = "tracking_no", length = 64)
    private String trackingNo;

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

    public PayChannel getPayChannel() {
        return payChannel;
    }

    public void setPayChannel(PayChannel payChannel) {
        this.payChannel = payChannel;
    }

    public String getShippedAt() {
        return shippedAt;
    }

    public void setShippedAt(String shippedAt) {
        this.shippedAt = shippedAt;
    }

    public ShipCompany getShipCompany() {
        return shipCompany;
    }

    public void setShipCompany(ShipCompany shipCompany) {
        this.shipCompany = shipCompany;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }
}
