package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 订单状态（SRS §5.4，BR-11 / BR-14）。
 *
 * <p>五状态顺序推进 + 一个独立的终态「已取消」：
 * <pre>
 *   客户下单 → 商家确认 → 备货完成 → 开始发货 → 交易完成
 *   客户下单 / 商家确认 / 备货完成：客户可取消
 *   客户下单 / 商家确认 / 备货完成 / 开始发货：卖家可取消
 *   任意可取消状态 --取消--> 已取消（终态）
 * </pre>
 *
 * <p>落库值使用小写英文 code，与既有 {@code ProductStatus} 的做法一致：显式转换器
 * 让列类型为 {@code varchar}，避免 Hibernate 为枚举列生成 check 约束（SQLite 不支持
 * {@code ALTER TABLE ADD CONSTRAINT}，枚举增删值会导致 {@code ddl-auto=update} 失败）。
 */
public enum OrderStatus {
    /** 客户下单：客户提交订单后的初始状态。 */
    placed("placed", "客户下单"),
    /** 商家确认：卖家已受理。 */
    confirmed("confirmed", "商家确认"),
    /** 备货完成：货已备好。 */
    packed("packed", "备货完成"),
    /** 开始发货：卖家开始线下交付，客户不能再取消。 */
    shipped("shipped", "开始发货"),
    /** 交易完成：钱货两清，终态，不可回退。 */
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

    /** 是否仍在途（客户下单 / 商家确认 / 备货完成 / 开始发货）。 */
    public boolean isInProgress() {
        return this != completed && this != cancelled;
    }

    /** 是否为终态（交易完成 / 已取消），终态不可再变更。 */
    public boolean isFinal() {
        return this == completed || this == cancelled;
    }

    /**
     * 客户侧可取消的范围：仅「开始发货」之前（SRS FR-C-09 / BR-15）。
     * 「开始发货」「交易完成」「已取消」均不提供客户取消入口。
     */
    public boolean isCancellableByCustomer() {
        return this == placed || this == confirmed || this == packed;
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
