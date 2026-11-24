package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 物流公司枚举（SRS §6.6、§10.1 S-14）。
 *
 * <p>枚举口径由团队自定：顺丰 / EMS / 中通 / 圆通 / 韵达。卖家推进到「开始发货」时
 * 必填物流单号、物流公司选填（BR-58）；选填不填时按顺丰落库，保证物流详情页
 * 永远有公司名可展示，而不是出现空白。
 */
public enum ShipCompany {
    sf("sf", "顺丰速运"),
    ems("ems", "EMS"),
    zto("zto", "中通快递"),
    yto("yto", "圆通速递"),
    yd("yd", "韵达快递");

    private final String code;
    private final String label;

    ShipCompany(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static ShipCompany of(String value) {
        if (value == null) return null;
        for (ShipCompany company : values()) {
            if (company.code.equals(value)) return company;
        }
        throw new IllegalArgumentException("未知物流公司: " + value);
    }

    @Converter(autoApply = true)
    public static class Persister implements AttributeConverter<ShipCompany, String> {
        @Override
        public String convertToDatabaseColumn(ShipCompany attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public ShipCompany convertToEntityAttribute(String dbData) {
            return of(dbData);
        }
    }
}
