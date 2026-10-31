package com.healix.core.vitals.support;

import com.healix.common.exception.BusinessException;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 体征数值生理合理范围（闭区间）。录入与计分共用，超出则拒绝写入 / 不计健康豆。
 *
 * <p>血糖单位 mmol/L；血压 mmHg；体温 ℃。
 */
public final class VitalValueRules {

    /** metricType → [min, max] */
    private static final Map<String, double[]> PLAUSIBLE = Map.ofEntries(
            Map.entry("BLOOD_GLUCOSE", new double[] {1, 35}),
            Map.entry("BLOOD_PRESSURE_SYS", new double[] {60, 260}),
            Map.entry("BLOOD_PRESSURE_DIA", new double[] {30, 160}),
            Map.entry("HEART_RATE", new double[] {30, 220}),
            Map.entry("WEIGHT", new double[] {20, 300}),
            Map.entry("HEIGHT", new double[] {50, 250}),
            Map.entry("WAIST", new double[] {40, 200}),
            Map.entry("HIP", new double[] {40, 200}),
            Map.entry("BODY_FAT", new double[] {3, 70}),
            Map.entry("TEMPERATURE", new double[] {34, 43}),
            Map.entry("SLEEP_HOURS", new double[] {0.5, 24}),
            Map.entry("STEPS", new double[] {1, 100000}));

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("BLOOD_GLUCOSE", "血糖"),
            Map.entry("BLOOD_PRESSURE_SYS", "收缩压"),
            Map.entry("BLOOD_PRESSURE_DIA", "舒张压"),
            Map.entry("HEART_RATE", "心率"),
            Map.entry("WEIGHT", "体重"),
            Map.entry("HEIGHT", "身高"),
            Map.entry("WAIST", "腰围"),
            Map.entry("HIP", "臀围"),
            Map.entry("BODY_FAT", "体脂率"),
            Map.entry("TEMPERATURE", "体温"),
            Map.entry("SLEEP_HOURS", "睡眠时长"),
            Map.entry("STEPS", "步数"));

    private VitalValueRules() {}

    public static boolean isPlausible(String metricType, BigDecimal value) {
        if (value == null || metricType == null || metricType.isBlank()) {
            return false;
        }
        double[] range = PLAUSIBLE.get(metricType.trim());
        if (range == null) {
            return true;
        }
        double v = value.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return false;
        }
        return v >= range[0] && v <= range[1];
    }

    /** 超出合理范围时抛业务异常；未知指标类型跳过（由调用方校验类型）。 */
    public static void assertPlausible(String metricType, BigDecimal value) {
        if (value == null) {
            throw new BusinessException(400, "请填写测量值");
        }
        if (metricType == null || metricType.isBlank()) {
            return;
        }
        String type = metricType.trim();
        double[] range = PLAUSIBLE.get(type);
        if (range == null) {
            return;
        }
        if (!isPlausible(type, value)) {
            String label = LABELS.getOrDefault(type, "测量值");
            String lo = formatBound(range[0]);
            String hi = formatBound(range[1]);
            throw new BusinessException(400, label + "需在 " + lo + "–" + hi + " 之间，请核对后重试");
        }
    }

    private static String formatBound(double n) {
        if (n == Math.rint(n) && Math.abs(n) < 1e12) {
            return String.valueOf((long) n);
        }
        return BigDecimal.valueOf(n).stripTrailingZeros().toPlainString();
    }
}
