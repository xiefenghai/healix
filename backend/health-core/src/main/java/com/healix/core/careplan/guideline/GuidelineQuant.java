package com.healix.core.careplan.guideline;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 条目量化约束：只支持服务端能从方案 JSON 算出来的指标。
 *
 * @param value 数值上 / 下限（{@code EXERCISE_INTENSITY_MAX} 时为 null）
 * @param level {@code EXERCISE_INTENSITY_MAX} 的强度上限：LIGHT / MODERATE / VIGOROUS
 * @param relatedRef {@code MONITOR_TASK_PRESENT} 要求存在的执行任务关联指标
 */
public record GuidelineQuant(
        String metric, String op, Double value, Double min, Double max, String level, String relatedRef, String unit) {

    public static final String WEEKLY_MODERATE_MIN = "EXERCISE_MODERATE_MIN_PER_WEEK";
    public static final String SESSION_MAX_MIN = "EXERCISE_SESSION_MAX_MIN";
    public static final String INTENSITY_MAX = "EXERCISE_INTENSITY_MAX";
    public static final String MONITOR_TASK_PRESENT = "MONITOR_TASK_PRESENT";
    public static final String WATER_ML_RANGE = "WATER_ML_RANGE";
    /** 每日热量缺口上限（kcal） */
    public static final String ENERGY_DEFICIT_KCAL = "ENERGY_DEFICIT_KCAL";
    /** 每周减重速度上限（kg） */
    public static final String WEIGHT_LOSS_RATE_KG_PER_WEEK = "WEIGHT_LOSS_RATE_KG_PER_WEEK";
    /** 称重频次：value 为每周最多次数（如 1=每周、7=每日） */
    public static final String WEIGH_FREQUENCY = "WEIGH_FREQUENCY";

    static final Set<String> METRICS = Set.of(
            WEEKLY_MODERATE_MIN,
            SESSION_MAX_MIN,
            INTENSITY_MAX,
            MONITOR_TASK_PRESENT,
            WATER_ML_RANGE,
            ENERGY_DEFICIT_KCAL,
            WEIGHT_LOSS_RATE_KG_PER_WEEK,
            WEIGH_FREQUENCY);
    static final List<String> INTENSITIES = List.of("LIGHT", "MODERATE", "VIGOROUS");

    /** 给模型与健管师看的一句话。 */
    public String describe() {
        return switch (metric) {
            case WEEKLY_MODERATE_MIN -> "每周中等强度运动" + op + " " + fmt(value) + " 分钟（高强度按 2 倍计）";
            case SESSION_MAX_MIN -> "单次运动 ≤ " + fmt(value) + " 分钟";
            case INTENSITY_MAX -> "运动强度不超过" + intensityLabel(level);
            case MONITOR_TASK_PRESENT -> "执行计划须含 " + relatedRef + " 监测任务";
            case WATER_ML_RANGE -> "每日饮水目标 " + fmt(min) + "–" + fmt(max) + " ml";
            case ENERGY_DEFICIT_KCAL -> "每日热量缺口" + op + " " + fmt(value) + " kcal";
            case WEIGHT_LOSS_RATE_KG_PER_WEEK -> "每周减重速度" + op + " " + fmt(value) + " kg";
            case WEIGH_FREQUENCY -> "称重频次 ≤ 每周 " + fmt(value) + " 次";
            default -> metric;
        };
    }

    static GuidelineQuant parse(JsonNode node, String where) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String metric = node.path("metric").asText("").trim().toUpperCase(Locale.ROOT);
        if (!METRICS.contains(metric)) {
            throw new IllegalStateException(where + " quant.metric 未知：" + metric);
        }
        String op = node.path("op").asText("").trim();
        Double value = node.hasNonNull("value") && node.get("value").isNumber() ? node.get("value").asDouble() : null;
        Double min = node.hasNonNull("min") ? node.get("min").asDouble() : null;
        Double max = node.hasNonNull("max") ? node.get("max").asDouble() : null;
        String level = node.hasNonNull("level") ? node.get("level").asText().trim().toUpperCase(Locale.ROOT) : null;
        String ref = node.hasNonNull("relatedRef") ? node.get("relatedRef").asText().trim() : null;
        switch (metric) {
            case WEEKLY_MODERATE_MIN -> require(value != null && (">=".equals(op) || "<=".equals(op)), where, "需 op(>=/<=) 与 value");
            case SESSION_MAX_MIN -> {
                require(value != null, where, "需 value");
                op = "<=";
            }
            case INTENSITY_MAX -> require(INTENSITIES.contains(level), where, "需 level(LIGHT/MODERATE/VIGOROUS)");
            case MONITOR_TASK_PRESENT -> require(ref != null && !ref.isEmpty(), where, "需 relatedRef");
            case WATER_ML_RANGE -> require(min != null && max != null && min <= max, where, "需 min ≤ max");
            case ENERGY_DEFICIT_KCAL, WEIGHT_LOSS_RATE_KG_PER_WEEK, WEIGH_FREQUENCY -> {
                require(value != null && (">=".equals(op) || "<=".equals(op) || op.isEmpty()), where, "需 value");
                if (op.isEmpty()) {
                    op = "<=";
                }
            }
            default -> { }
        }
        return new GuidelineQuant(metric, op, value, min, max, level, ref, node.path("unit").asText(null));
    }

    static int intensityRank(String level) {
        return level == null ? -1 : INTENSITIES.indexOf(level.trim().toUpperCase(Locale.ROOT));
    }

    static String intensityLabel(String level) {
        return switch (level == null ? "" : level) {
            case "LIGHT" -> "低强度";
            case "MODERATE" -> "中等强度";
            case "VIGOROUS" -> "高强度";
            default -> String.valueOf(level);
        };
    }

    static String fmt(Double v) {
        if (v == null) {
            return "";
        }
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(v);
    }

    private static void require(boolean ok, String where, String msg) {
        if (!ok) {
            throw new IllegalStateException(where + " quant " + msg);
        }
    }
}
