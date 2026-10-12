package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 商品状态（对应 SRS §3.3 商品状态机）。
 *
 * <p>状态机共五个状态，其中"交易失败"是<b>不停留的过渡态</b>：在标记失败的
 * 同一次请求内即完成"处置当前买家意向 + 分流"，因此<b>不落库</b>。
 * 落库的持久化状态只有以下四个值。
 *
 * <p>落库值使用小写字面量（active / frozen / resumed / sold），与前端
 * admin.js 中的 statusClass 判定、以及原有 data.json 的取值保持一致。
 */
public enum ProductStatus {
    /** 在售：对外展示、可接受购买意向。 */
    active("active"),
    /** 冻结：暂停接受新意向。自动冻结（开始交易）与手动冻结为同一状态。 */
    frozen("frozen"),
    /** 恢复在售：交易失败处置完毕且队列已空后重新可售，行为与在售完全等价。 */
    resumed("resumed"),
    /** 下架（终态）：交易成功，永久撤下并进入历史，不可撤销。 */
    sold("sold");

    private final String code;

    ProductStatus(String code) {
        this.code = code;
    }

    /** 落库 / 出参使用的字面量。 */
    public String code() {
        return code;
    }

    /** 是否为"可接受购买意向"的状态（在售、恢复在售）。 */
    public boolean isBuyable() {
        return this == active || this == resumed;
    }

    public static ProductStatus of(String value) {
        if (value == null) return null;
        for (ProductStatus status : values()) {
            if (status.code.equals(value)) return status;
        }
        throw new IllegalArgumentException("未知商品状态: " + value);
    }

    /**
     * 落库转换器：以 {@link #code()} 小写字面量存取。
     *
     * <p>刻意不使用 {@code @Enumerated(EnumType.STRING)}：那样 Hibernate 6 会为
     * 枚举列生成 {@code check (status in ('active',...))} 约束，而 SQLite 不支持
     * {@code ALTER TABLE ADD CONSTRAINT}，一旦枚举增删值后续 {@code ddl-auto=update}
     * 就会在建表/校验阶段报错。改用显式转换器后列类型为 {@code varchar(16)}，
     * 取值仍由 Java 枚举保证，既便于阅读又便于演进。
     */
    @Converter(autoApply = true)
    public static class Persister implements AttributeConverter<ProductStatus, String> {
        @Override
        public String convertToDatabaseColumn(ProductStatus attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public ProductStatus convertToEntityAttribute(String dbData) {
            return of(dbData);
        }
    }
}
