package com.coursewise.shopping.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * 支付渠道（SRS §6.6、BR-52）。
 *
 * <p>四种渠道全支持、由客户自选，支付成功时把渠道记录在订单上，供取消 / 售后时<b>原路退款</b>
 * （BR-17 / BR-70）。落库值与 {@link OrderStatus} 一致使用小写英文 code，同样是
 * {@code varchar} 列 + 显式转换器，避免 SQLite 上的枚举 check 约束问题。
 */
public enum PayChannel {
    /** 银行卡。 */
    bankCard("bank_card", "银行卡"),
    /** 信用卡。 */
    creditCard("credit_card", "信用卡"),
    /** 支付宝。 */
    alipay("alipay", "支付宝"),
    /** 微信支付。 */
    wechat("wechat", "微信支付");

    private final String code;
    private final String label;

    PayChannel(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
    }

    public static PayChannel of(String value) {
        if (value == null) return null;
        for (PayChannel channel : values()) {
            if (channel.code.equals(value)) return channel;
        }
        throw new IllegalArgumentException("未知支付渠道: " + value);
    }

    @Converter(autoApply = true)
    public static class Persister implements AttributeConverter<PayChannel, String> {
        @Override
        public String convertToDatabaseColumn(PayChannel attribute) {
            return attribute == null ? null : attribute.code();
        }

        @Override
        public PayChannel convertToEntityAttribute(String dbData) {
            return of(dbData);
        }
    }
}
