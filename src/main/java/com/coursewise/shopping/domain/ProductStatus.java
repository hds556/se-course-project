package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 在售、已下架两种业务状态。两个下架代码同时表示不同成因。 */
public enum ProductStatus {
    /** 在售：对外展示并接收订单。 */
    active("active"),
    /** 售罄下架，库存回正后恢复。 */
    depleted("depleted"),
    /** 手动下架，无重新上架路径。 */
    withdrawn("withdrawn");

    private final String code;

    ProductStatus(String code) {
        this.code = code;
    }

    /** 落库 / 出参使用的字面量。 */
    public String code() {
        return code;
    }

    public boolean isBuyable() {
        return this == active;
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
     * 枚举列生成 {@code check (status in (...))} 约束，而 SQLite 不支持
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
