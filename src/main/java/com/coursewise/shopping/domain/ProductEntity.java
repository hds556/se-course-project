package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 商品（SRS §4.1）。系统中任意时刻至多一件未售出商品（status != sold）。
 *
 * <p>商品发布后，名称/描述/图片/价格在任何状态下均不得修改（BR-14）。
 */
@Entity
@Table(name = "product", indexes = {
        @Index(name = "idx_product_status", columnList = "status")
})
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 2000)
    private String description;

    /** 形如 /uploads/p<时间戳>-<随机>.jpg */
    @Column(nullable = false, length = 255)
    private String image;

    /** 价格以"分"存储，避免浮点误差（BR-17：> 0、两位小数、人民币）。 */
    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(nullable = false, length = 16)
    private ProductStatus status;

    /**
     * 当前"交易中"的意向 ID；为 null 表示没有正在进行的交易
     * （此时若 status=frozen 则为"手动冻结"）。
     *
     * <p>刻意<b>不建外键</b>：交易失败处置时会先解绑、后结案，硬外键会挡住
     * 合法的中间态。一致性由 Service 层保证（对应原 tradingIntent() 逻辑）。
     */
    @Column(name = "trading_intent_id")
    private Long tradingIntentId;

    @Column(name = "created_at", nullable = false, length = 32)
    private String createdAt;

    /** 关键操作时间戳（BR-19）：进入冻结的时间。 */
    @Column(name = "frozen_at", length = 32)
    private String frozenAt;

    /** 交易结果，成功时为 "success"。 */
    @Column(length = 32)
    private String result;

    /** 关键操作时间戳（BR-19）：标记交易结果的时间。 */
    @Column(name = "result_at", length = 32)
    private String resultAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public long getPriceCents() {
        return priceCents;
    }

    public void setPriceCents(long priceCents) {
        this.priceCents = priceCents;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public void setStatus(ProductStatus status) {
        this.status = status;
    }

    public Long getTradingIntentId() {
        return tradingIntentId;
    }

    public void setTradingIntentId(Long tradingIntentId) {
        this.tradingIntentId = tradingIntentId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getFrozenAt() {
        return frozenAt;
    }

    public void setFrozenAt(String frozenAt) {
        this.frozenAt = frozenAt;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getResultAt() {
        return resultAt;
    }

    public void setResultAt(String resultAt) {
        this.resultAt = resultAt;
    }

    /** 是否可接受新的购买意向。 */
    public boolean isBuyable() {
        return status != null && status.isBuyable();
    }

    /** 是否处于"交易中"（已冻结且有绑定买家）。 */
    public boolean isTrading() {
        return status == ProductStatus.frozen && tradingIntentId != null;
    }
}
