package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 意向结局（SRS §3.4）：成功 / 作废 / 重新排队 / 撤销 / 未成交。
 *
 * <p>"重新排队"以"旧记录结案 + 新记录入队尾"的方式实现（BR-07）。
 * 落库值使用小写字面量，与前端 admin.js 的 intentOutcome 映射保持一致。
 */
public enum IntentOutcome {
    /** 成功：该买家买下商品。 */
    success("success"),
    /** 作废：交易失败后卖家选择直接终结该意向。 */
    voided("voided"),
    /** 重新排队：交易失败后卖家选择让该买家排到队尾，旧口令码失效。 */
    requeued("requeued"),
    /** 撤销：买家凭口令码自行撤销。 */
    cancelled("cancelled"),
    /** 未成交：商品被更早排队者买走后，队列中其余意向的结局。 */
    unsold("unsold");

    private final String code;

    IntentOutcome(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static IntentOutcome of(String value) {
        if (value == null) return null;
        for (IntentOutcome outcome : values()) {
            if (outcome.code.equals(value)) return outcome;
        }
        throw new IllegalArgumentException("未知意向结局: " + value);
    }

    /** 落库转换器：以 {@link #code()} 小写字面量存取，避免 SQLite 不支持 ALTER 加约束的问题。 */
    @Converter(autoApply = true)
    public static class Persister implements AttributeConverter<IntentOutcome, String> {
        @Override
        public String convertToDatabaseColumn(IntentOutcome attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public IntentOutcome convertToEntityAttribute(String dbData) {
            return of(dbData);
        }
    }
}
