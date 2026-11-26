package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 订单状态（SRS §5.4，BR-11 / BR-12 / BR-13）。
 *
 * <p>挑战需求C 起由五状态扩为<b>七状态顺序推进 + 一个独立终态</b>：
 * <pre>
 *   客户下单 → 待支付 → 已支付 → 商家确认 → 备货完成 → 开始发货 → 交易完成
 *   客户下单 / 待支付：客户可取消（未付款段，无退款），超 24h 未支付由系统自动取消
 *   已支付 / 商家确认 / 备货完成：客户可取消（已付款段，原路退款）
 *   客户下单 / 待支付 / 已支付 / 商家确认 / 备货完成 / 开始发货：卖家可取消
 *   开始发货 --客户确认收货--> 交易完成（终态）
 *   任意可取消状态 --取消--> 已取消（终态）
 * </pre>
 *
 * <p>落库值使用小写英文 code，与既有 {@code ProductStatus} 的做法一致：显式转换器
 * 让列类型为 {@code varchar}，避免 Hibernate 为枚举列生成 check 约束（SQLite 不支持
 * {@code ALTER TABLE ADD CONSTRAINT}，枚举增删值会导致 {@code ddl-auto=update} 失败）。
 * 新增的两个取值 {@code pendingPayment} / {@code paid} 因此可以在既有库上平滑升级。
 */
public enum OrderStatus {
    /** 客户下单：客户提交订单后的初始状态。 */
    placed("placed", "客户下单"),
    /** 待支付：客户已发起支付、尚未支付成功（FR-C-09）。 */
    pendingPayment("pending_payment", "待支付"),
    /** 已支付：模拟支付成功，支付渠道已记录（FR-C-09）。 */
    paid("paid", "已支付"),
    /** 商家确认：卖家已受理。 */
    confirmed("confirmed", "商家确认"),
    /** 备货完成：货已备好。 */
    packed("packed", "备货完成"),
    /** 开始发货：卖家已交运并填写物流单号，客户不能再取消。 */
    shipped("shipped", "开始发货"),
    /** 交易完成：客户确认收货后进入，终态，不可回退。 */
    completed("completed", "交易完成"),
    /** 已取消：独立终态。 */
    cancelled("cancelled", "已取消");

    private final String code;
    private final String label;

    OrderStatus(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    /** 中文展示名，用于接口出参与前端标签。 */
    public String label() {
        return label;
    }

    /** 是否仍在途（除「交易完成」「已取消」之外的全部状态）。 */
    public boolean isInProgress() {
        return this != completed && this != cancelled;
    }

    /** 是否为终态（交易完成 / 已取消），终态不可再变更。 */
    public boolean isFinal() {
        return this == completed || this == cancelled;
    }

    /**
     * 客户侧可取消的范围：仅「开始发货」之前（SRS FR-C-10 / BR-15）。
     * 「开始发货」「交易完成」「已取消」均不提供客户取消入口。
     */
    public boolean isCancellableByCustomer() {
        return this == placed || this == pendingPayment || this == paid
                || this == confirmed || this == packed;
    }

    /**
     * 是否已付款（BR-17 / BR-55）：已支付及之后的在途状态，取消时必须原路退款。
     * 未付款段（客户下单 / 待支付）取消不产生退款。
     */
    public boolean isPaidStage() {
        return this == paid || this == confirmed || this == packed || this == shipped
                || this == completed;
    }

    /** 是否未付款（客户下单 / 待支付）。 */
    public boolean isUnpaidStage() {
        return this == placed || this == pendingPayment;
    }

    /**
     * 是否仍在 24h 未支付超时窗口内（BR-21 / C-31）：只有「客户下单」「待支付」会被系统超时取消。
     */
    public boolean isAwaitingPayment() {
        return this == placed || this == pendingPayment;
    }

    /** 是否可由客户发起支付（FR-C-09）：只有「客户下单」「待支付」两态有支付入口。 */
    public boolean isPayableByCustomer() {
        return isAwaitingPayment();
    }

    /** 是否可由客户确认收货（FR-C-11）：只有「开始发货」有确认收货入口。 */
    public boolean isConfirmableByCustomer() {
        return this == shipped;
    }

    /**
     * 是否可由卖家推进（FR-S-08 / S-12）：仅「已支付 / 商家确认 / 备货完成」。
     *
     * <p>「客户下单」「待支付」未付款，卖家不得受理；「开始发货」的下一格是客户确认收货，
     * 不由卖家标记；「交易完成」「已取消」均为终态。
     */
    public boolean isSellerAdvanceable() {
        return this == paid || this == confirmed || this == packed;
    }

    /**
     * 是否存在「已付款」订单（C-27）：用于商品调库存 0 / 手动下架的拦截。
     * 只统计仍在途的已付款段，交易完成与已取消不构成阻塞。
     */
    public boolean blocksProductWithdrawal() {
        return this == paid || this == confirmed || this == packed;
    }

    /**
     * 是否可查询物流（FR-C-12）：发货后（开始发货 / 交易完成）才有物流信息；
     * 追加「已取消」是为了已发货后被取消的订单仍能查看历史轨迹，未发货订单一律无物流。
     */
    public boolean hasLogistics() {
        return this == shipped || this == completed || this == cancelled;
    }

    public static OrderStatus of(String value) {
        if (value == null) return null;
        for (OrderStatus status : values()) {
            if (status.code.equals(value)) return status;
        }
        throw new IllegalArgumentException("未知订单状态: " + value);
    }

    /** 落库转换器：以 {@link #code()} 存取。 */
    @Converter(autoApply = true)
    public static class Persister implements AttributeConverter<OrderStatus, String> {
        @Override
        public String convertToDatabaseColumn(OrderStatus attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public OrderStatus convertToEntityAttribute(String dbData) {
            return of(dbData);
        }
    }
}
