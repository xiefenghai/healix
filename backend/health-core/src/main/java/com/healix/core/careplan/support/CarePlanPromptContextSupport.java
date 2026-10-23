package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.people.support.DiseaseCodeLabels;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 管理方案 LLM 上下文清洗：去掉占位病种、补生活方式中文、格式化检验/体征，并过滤明显异常指标。
 */
public final class CarePlanPromptContextSupport {

    /** 写入库允许更宽；喂给模型用成人常用区间，避免 40cm 腰围等脏数据带偏方案。 */
    private static final Map<String, double[]> PROMPT_METRIC_RANGE = Map.of(
            "BLOOD_GLUCOSE", new double[] {1, 35},
            "BLOOD_PRESSURE_SYS", new double[] {60, 260},
            "BLOOD_PRESSURE_DIA", new double[] {30, 160},
            "HEART_RATE", new double[] {30, 220},
            "WEIGHT", new double[] {30, 250},
            "HEIGHT", new double[] {100, 230},
            "WAIST", new double[] {50, 180},
            "TEMPERATURE", new double[] {34, 43},
            "SLEEP_HOURS", new double[] {0.5, 24},
            "STEPS", new double[] {1, 100000});

    private static final Map<String, String> METRIC_LABELS = Map.of(
            "BLOOD_GLUCOSE", "血糖",
            "BLOOD_PRESSURE_SYS", "收缩压",
            "BLOOD_PRESSURE_DIA", "舒张压",
            "HEART_RATE", "心率",
            "WEIGHT", "体重",
            "HEIGHT", "身高",
            "WAIST", "腰围",
            "TEMPERATURE", "体温",
            "SLEEP_HOURS", "睡眠时长",
            "STEPS", "步数");

    private static final Map<String, String> DIET_TYPE = Map.of(
            "NORMAL", "普通饮食",
            "VEGETARIAN", "素食",
            "LOW_SALT", "低盐",
            "LOW_SUGAR", "低糖",
            "LOW_SALT_SUGAR", "低盐低糖",
            "OTHER", "其他");

    private static final Map<String, String> DIET_HABIT = Map.of("GOOD", "好", "FAIR", "一般", "POOR", "差");

    private static final Map<String, String> EXERCISE_FREQUENCY = Map.of(
            "NONE", "基本不运动",
            "WEEKLY_3", "每周≥3次",
            "WEEKLY_1", "每周1-2次",
            "DAILY", "每天");

    private static final Map<String, String> EXERCISE_INTENSITY =
            Map.of("LIGHT", "轻度", "MODERATE", "中度", "VIGOROUS", "重度");

    private CarePlanPromptContextSupport() {}

    public static boolean isMeaningfulDiseaseCode(String code) {
        return DiseaseCodeLabels.isMeaningful(code);
    }

    public static ObjectNode summarizeBasicArchive(JsonNode content) {
        ObjectNode o = JsonUtils.emptyObject();
        ArrayNode illnessCodes = o.putArray("presentIllness");
        ArrayNode illnessLabels = o.putArray("presentIllnessLabels");
        JsonNode present = content.path("presentIllness");
        if (present.isArray()) {
            for (JsonNode n : present) {
                if (n == null || !n.isTextual()) {
                    continue;
                }
                String code = n.asText();
                if (!isMeaningfulDiseaseCode(code)) {
                    continue;
                }
                illnessCodes.add(code);
                String label = DiseaseCodeLabels.label(code);
                if (StringUtils.hasText(label)) {
                    illnessLabels.add(label);
                }
            }
        }
        if (content.has("diet")) {
            o.set("diet", enrichLifestyleBlock(content.get("diet"), true));
        }
        if (content.has("exercise")) {
            o.set("exercise", enrichLifestyleBlock(content.get("exercise"), false));
        }
        return o;
    }

    static JsonNode enrichLifestyleBlock(JsonNode raw, boolean diet) {
        if (raw == null || raw.isNull() || !raw.isObject()) {
            return raw == null ? JsonUtils.emptyObject() : raw;
        }
        ObjectNode out = ((ObjectNode) raw).deepCopy();
        if (diet) {
            putEnumLabel(out, "type", DIET_TYPE);
            putEnumLabel(out, "habit", DIET_HABIT);
        } else {
            putEnumLabel(out, "frequency", EXERCISE_FREQUENCY);
            putEnumLabel(out, "intensity", EXERCISE_INTENSITY);
        }
        return out;
    }

    private static void putEnumLabel(ObjectNode node, String field, Map<String, String> labels) {
        String code = text(node, field);
        if (!StringUtils.hasText(code)) {
            return;
        }
        String label = labels.get(code.trim().toUpperCase(Locale.ROOT));
        if (StringUtils.hasText(label)) {
            node.put(field + "Label", label);
        }
    }

    public static boolean isMetricUsefulForPrompt(String metricType, BigDecimal value) {
        if (value == null || !StringUtils.hasText(metricType)) {
            return false;
        }
        double[] range = PROMPT_METRIC_RANGE.get(metricType.trim());
        if (range == null) {
            return true;
        }
        double v = value.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return false;
        }
        return v >= range[0] && v <= range[1];
    }

    public static String metricTypeLabel(String metricType) {
        if (!StringUtils.hasText(metricType)) {
            return "";
        }
        return METRIC_LABELS.getOrDefault(metricType.trim(), metricType.trim());
    }

    public static String formatMetricValue(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    public static String formatLabItemSummary(String itemName, BigDecimal valueNum, String valueText, String unit) {
        String name = StringUtils.hasText(itemName) ? itemName.trim() : "检验项";
        String value;
        if (valueNum != null) {
            value = formatMetricValue(valueNum);
        } else if (StringUtils.hasText(valueText)) {
            value = valueText.trim();
        } else {
            value = "";
        }
        return name + "=" + joinValueAndUnit(value, unit);
    }

    static String joinValueAndUnit(String value, String unit) {
        if (!StringUtils.hasText(unit)) {
            return value == null ? "" : value;
        }
        String u = unit.trim();
        String v = value == null ? "" : value;
        if (looksLikeScientificUnit(u)) {
            // 7.55 + 10^9/L → 7.55×10^9/L，避免 7.550010^9/L
            if (u.startsWith("×") || u.startsWith("x") || u.startsWith("X")) {
                return v + u;
            }
            return v + "×" + u;
        }
        if (!StringUtils.hasText(v)) {
            return u;
        }
        // 普通单位用空格分隔，避免 145.0000g/L 粘连难读
        return v + " " + u;
    }

    private static boolean looksLikeScientificUnit(String unit) {
        String u = unit.toLowerCase(Locale.ROOT);
        return u.startsWith("10^")
                || u.startsWith("10＾")
                || u.startsWith("×10")
                || u.startsWith("x10")
                || u.startsWith("*10");
    }

    public static String suspectMetricNote(String metricType, BigDecimal value, String unit) {
        String label = metricTypeLabel(metricType);
        String formatted = formatMetricValue(value);
        String u = StringUtils.hasText(unit) ? unit.trim() : "";
        return label + " " + formatted + u + " 超出成人常用范围，已忽略";
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String t = v.asText();
        return StringUtils.hasText(t) ? t.trim() : null;
    }
}
