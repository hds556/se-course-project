package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 意向阶段（SRS §3.4）：排队中 / 交易中 / 已结束。 */
public enum IntentStage {
    /** 排队中：已登记在队列里，等待轮流进入交易。 */
    queued("queued"),
    /** 交易中：卖家正与该买家走线下交易流程，同一商品同一时刻至多一条。 */
    trading("trading"),
    /** 已结束：意向已终结，结局见 {@link IntentOutcome}，口令码随之失效。 */
    closed("closed");

    private final String code;

    IntentStage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static IntentStage of(String value) {
        if (value == null) return null;
        for (IntentStage stage : values()) {
            if (stage.code.equals(value)) return stage;
        }
        throw new IllegalArgumentException("未知意向阶段: " + value);
    }

    /** 落库转换器：以 {@link #code()} 小写字面量存取，避免 SQLite 不支持 ALTER 加约束的问题。 */
    @Converter(autoApply = true)
    public static class Persister implements AttributeConverter<IntentStage, String> {
        @Override
        public String convertToDatabaseColumn(IntentStage attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public IntentStage convertToEntityAttribute(String dbData) {
            return of(dbData);
        }
    }
}
