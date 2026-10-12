package com.coursewise.shopping.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 购买意向（SRS §4.2）。每次提交都是一条独立记录，不按联系方式合并（BR-10）。
 *
 * <p>位次<b>不落库</b>，由 (submittedAt, id) 排序算出：
 * 同商品下 stage=queued 且排序在前的条数 + 1。
 *
 * <p>唯一的硬约束是 passcode：口令码是买家认领自己意向的唯一凭证（BR-13），
 * 由列级 UNIQUE 约束在数据库层兜底，杜绝并发下的重复发放。
 */
@Entity
@Table(name = "intent", indexes = {
        @Index(name = "idx_intent_product", columnList = "product_id"),
        @Index(name = "idx_intent_queue", columnList = "product_id, stage, submitted_at, id")
})
public class IntentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 关联商品（必填）。
     *
     * <p>刻意<b>不建数据库外键</b>：SQLite 的 JDBC 驱动默认
     * {@code foreign_keys=OFF}，外键实际不参与执行，反而让 {@code ddl-auto=update}
     * 每轮启动都去比对约束。一致性由服务层保证——意向只会挂在已有的未售出商品上，
     * 且商品永不被删除。
     */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** 姓名（可凭口令码修改，BR-12）。 */
    @Column(nullable = false, length = 64)
    private String name;

    /** 联系电话（可凭口令码修改，BR-12）。 */
    @Column(nullable = false, length = 32)
    private String phone;

    /**
     * 口令码：买家认领自己意向的唯一凭证（BR-13）。
     *
     * <p>用 {@code unique = true} 而非 {@code @Table(uniqueConstraints=...)}：
     * 后者在 Hibernate 6 + SQLite 下走"先建唯一索引"路径，而该索引不被
     * {@code UniqueConstraintSchemaGenerator} 识别，导致建表后又被删掉。
     * 列级 unique 直接内联进 CREATE TABLE，约束真实生效。
     */
    @Column(nullable = false, length = 16, unique = true)
    private String passcode;

    /** 提交时间，决定排队位次，不可变（SRS §4.2）。 */
    @Column(name = "submitted_at", nullable = false, length = 32)
    private String submittedAt;

    @Column(nullable = false, length = 16)
    private IntentStage stage;

    /** 终结后的结局；未终结时为 null。 */
    @Column(length = 16)
    private IntentOutcome outcome;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getPasscode() {
        return passcode;
    }

    public void setPasscode(String passcode) {
        this.passcode = passcode;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(String submittedAt) {
        this.submittedAt = submittedAt;
    }

    public IntentStage getStage() {
        return stage;
    }

    public void setStage(IntentStage stage) {
        this.stage = stage;
    }

    public IntentOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(IntentOutcome outcome) {
        this.outcome = outcome;
    }

    /** 是否仍在排队（位次有效）。 */
    public boolean isQueued() {
        return stage == IntentStage.queued;
    }

    /** 是否已终结。 */
    public boolean isClosed() {
        return stage == IntentStage.closed;
    }
}
