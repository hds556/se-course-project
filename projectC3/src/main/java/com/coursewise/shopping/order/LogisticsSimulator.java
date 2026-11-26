package com.coursewise.shopping.order;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 模拟物流轨迹（FR-C-12、BR-60、§10.1 S-16）。
 *
 * <p>系统不接真实物流 API（EX-03），跟踪信息为<b>模拟数据</b>：枚举固定为
 * 「已发货 / 运输中 / 已签收」三档，按「当前状态 + 距下单时间的时长」确定档位，
 * 由 [发货时刻, 现在] 之间的进度推算，因此同一订单反复查询时轨迹单调前进、不会倒退，
 * 也不需要在库里存任何轨迹表（S-21：物流走模拟数据）。
 *
 * <p>轨迹时间点同样由发货时间推算，只做展示，不参与任何业务判断。
 */
final class LogisticsSimulator {

    /** 时间点展示统一用东八区，与 {@code spring.jpa.properties.hibernate.jdbc.time_zone} 一致。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 各档位于发货后多久出现：交运即「已发货」，满 1 天「运输中」，满 3 天「已签收」。 */
    private static final Duration DEPARTED_AFTER = Duration.ZERO;
    private static final Duration IN_TRANSIT_AFTER = Duration.ofDays(1);
    private static final Duration SIGNED_AFTER = Duration.ofDays(3);

    /** 节点名称。 */
    static final String DEPARTED = "已发货";
    static final String IN_TRANSIT = "运输中";
    static final String SIGNED = "已签收";

    private LogisticsSimulator() {
    }

    /**
     * 依据发货时间与当前时间生成轨迹。
     *
     * @param shippedAt 发货时间（ISO 8601）；为 {@code null} 时按「刚发货」处理
     * @param now       当前时间
     * @param company   物流公司展示名
     * @param city      物流城市（取收货地址快照的首段，缺失时为「发件仓」）
     * @return 轨迹节点，按时间倒序（最新在最前）
     */
    static List<Trace> traces(String shippedAt, Instant now, String company, String city) {
        Instant departAt = parse(shippedAt, now);
        Duration elapsed = Duration.between(departAt, now);
        if (elapsed.isNegative()) elapsed = Duration.ZERO;

        List<Trace> traces = new ArrayList<>();
        // 最新节点在最前，与常见物流查询页一致
        if (elapsed.compareTo(SIGNED_AFTER) >= 0) {
            traces.add(new Trace(SIGNED, format(departAt.plus(SIGNED_AFTER)),
                    "您的快件已签收，签收人：本人。" + company + "感谢您的使用"));
        }
        if (elapsed.compareTo(IN_TRANSIT_AFTER) >= 0) {
            traces.add(new Trace(IN_TRANSIT, format(departAt.plus(IN_TRANSIT_AFTER)),
                    "快件已到达【" + city + "转运中心】，正在发往收货地址"));
        }
        if (elapsed.compareTo(DEPARTED_AFTER) >= 0) {
            traces.add(new Trace(DEPARTED, format(departAt),
                    "卖家已交寄，" + company + "已揽收，运单号已生效"));
        }
        return traces;
    }

    /** 当前档位：签收 / 运输中 / 已发货。 */
    static String currentStage(String shippedAt, Instant now) {
        Instant departAt = parse(shippedAt, now);
        Duration elapsed = Duration.between(departAt, now);
        if (elapsed.isNegative()) elapsed = Duration.ZERO;
        if (elapsed.compareTo(SIGNED_AFTER) >= 0) return SIGNED;
        if (elapsed.compareTo(IN_TRANSIT_AFTER) >= 0) return IN_TRANSIT;
        return DEPARTED;
    }

    /** 从收货地址快照里取一个城市名用于模拟轨迹文案（取第一个「市」之前的部分）。 */
    static String cityOf(String addressSnapshot) {
        if (addressSnapshot == null || addressSnapshot.isBlank()) return "发件仓";
        String text = addressSnapshot.trim();
        int index = text.indexOf('市');
        if (index > 0 && index <= 12) return text.substring(0, index + 1);
        return text.length() <= 12 ? text : text.substring(0, 12);
    }

    private static Instant parse(String shippedAt, Instant fallback) {
        if (shippedAt == null || shippedAt.isBlank()) return fallback;
        try {
            return Instant.parse(shippedAt);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String format(Instant instant) {
        return TIME.format(instant.atZone(ZONE));
    }

    /** 单个轨迹节点。 */
    record Trace(String stage, String time, String description) {
    }
}
