package com.healix.core.vitals.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/**
 * 体征 / 目标值跨字段质检：绝对区间 + 腰围与 BMI/身高一致性等。
 * 供首诊 AI 总结打标、管理方案 prompt 剔除脏数据共用。
 */
public final class MetricPlausibility {

    public record Hit(String field, String metricType, String label, String reason) {}

    private MetricPlausibility() {}

    /** 方案 prompt：单值是否可用；腰围可带身高体重做一致性。 */
    public static boolean isUsefulForPrompt(
            String metricType, BigDecimal value, BigDecimal heightCm, BigDecimal weightKg) {
        if (value == null || !StringUtils.hasText(metricType)) {
            return false;
        }
        String type = metricType.trim();
        if (!isInPromptRange(type, value)) {
            return false;
        }
        if ("WAIST".equals(type)) {
            return waistConsistent(value, heightCm, weightKg) == null;
        }
        return true;
    }

    public static boolean isUsefulForPrompt(String metricType, BigDecimal value) {
        return isUsefulForPrompt(metricType, value, null, null);
    }

    /** 首诊答卷质检：返回需标红 / 不可用于评估的字段。 */
    public static List<Hit> assessIntakeAnswers(JsonNode answers) {
        List<Hit> out = new ArrayList<>();
        if (answers == null || !answers.isObject()) {
            return out;
        }
        BigDecimal height = decimal(answers.get("metric.HEIGHT"));
        BigDecimal weight = decimal(answers.get("metric.WEIGHT"));
        BigDecimal waist = decimal(answers.get("metric.WAIST"));
        BigDecimal sys = decimal(answers.get("metric.BLOOD_PRESSURE_SYS"));
        BigDecimal dia = decimal(answers.get("metric.BLOOD_PRESSURE_DIA"));

        if (waist != null) {
            if (!isInPromptRange("WAIST", waist)) {
                out.add(hit("metric.WAIST", "WAIST", "腰围", "数值超出成人常用范围，疑似录入错误"));
            } else {
                String mismatch = waistConsistent(waist, height, weight);
                if (mismatch != null) {
                    out.add(hit("metric.WAIST", "WAIST", "腰围", mismatch));
                }
            }
        }
        if (sys != null && !isInPromptRange("BLOOD_PRESSURE_SYS", sys)) {
            out.add(hit("metric.BLOOD_PRESSURE_SYS", "BLOOD_PRESSURE_SYS", "收缩压", "数值异常，疑似录入错误"));
        }
        if (dia != null && !isInPromptRange("BLOOD_PRESSURE_DIA", dia)) {
            out.add(hit("metric.BLOOD_PRESSURE_DIA", "BLOOD_PRESSURE_DIA", "舒张压", "数值异常，疑似录入错误"));
        }
        if (sys != null && dia != null) {
            if (sys.compareTo(dia) < 0) {
                out.add(hit("metric.BLOOD_PRESSURE_SYS", "BLOOD_PRESSURE_SYS", "收缩压", "高压低于低压，疑似填反"));
            } else if (sys.subtract(dia).doubleValue() < 20) {
                out.add(hit(
                        "metric.BLOOD_PRESSURE_DIA",
                        "BLOOD_PRESSURE_DIA",
                        "舒张压",
                        "高低压差过小，疑似录入错误"));
            }
        }

        // 下限对齐 field-catalog min，避免目录允许值仍被标「录入错误」
        BigDecimal targetDia = decimal(answers.get("goal.targetBpDia"));
        if (targetDia != null && (targetDia.doubleValue() < 40 || targetDia.doubleValue() > 110)) {
            out.add(hit("goal.targetBpDia", "BP_DIA_TARGET", "舒张压目标", "目标值不合理，疑似录入错误"));
        }
        BigDecimal targetSys = decimal(answers.get("goal.targetBpSys"));
        if (targetSys != null && (targetSys.doubleValue() < 80 || targetSys.doubleValue() > 180)) {
            out.add(hit("goal.targetBpSys", "BP_SYS_TARGET", "收缩压目标", "目标值不合理，疑似录入错误"));
        }
        BigDecimal targetHba1c = decimal(answers.get("goal.targetHba1c"));
        if (targetHba1c != null && (targetHba1c.doubleValue() < 4 || targetHba1c.doubleValue() > 8.0)) {
            out.add(hit("goal.targetHba1c", "HBA1C_TARGET", "糖化目标", "目标值不合理，疑似录入错误，不可用于评估"));
        }
        BigDecimal targetFpg = decimal(answers.get("goal.targetFpg"));
        if (targetFpg != null && (targetFpg.doubleValue() < 3 || targetFpg.doubleValue() > 10)) {
            out.add(hit("goal.targetFpg", "FPG_TARGET", "空腹血糖目标", "目标值不合理，疑似录入错误，不可用于评估"));
        }
        return out;
    }

    /**
     * 腰围与体型一致性。BMI≥24 时腰围过小（相对身高）视为录入错误。
     *
     * @return 原因文案；一致则 null
     */
    public static String waistConsistent(BigDecimal waistCm, BigDecimal heightCm, BigDecimal weightKg) {
        if (waistCm == null) {
            return null;
        }
        double w = waistCm.doubleValue();
        Double bmi = bmi(heightCm, weightKg);
        if (heightCm != null) {
            double h = heightCm.doubleValue();
            if (h >= 140 && w < h * 0.40 && bmi != null && bmi >= 24) {
                return "与体重/BMI 不匹配，疑似录入错误，不可用于评估";
            }
            if (h >= 140 && w < h * 0.35) {
                return "与身高不匹配，疑似录入错误，不可用于评估";
            }
        }
        if (bmi != null && bmi >= 25 && w < 75) {
            return "与体重/BMI 不匹配，疑似录入错误，不可用于评估";
        }
        if (bmi != null && bmi >= 28 && w < 80) {
            return "与体重/BMI 不匹配，疑似录入错误，不可用于评估";
        }
        return null;
    }

    public static Double bmi(BigDecimal heightCm, BigDecimal weightKg) {
        if (heightCm == null || weightKg == null) {
            return null;
        }
        double h = heightCm.doubleValue();
        double kg = weightKg.doubleValue();
        if (h < 50 || h > 250 || kg < 10 || kg > 400) {
            return null;
        }
        double m = h / 100.0;
        if (m <= 0) {
            return null;
        }
        return kg / (m * m);
    }

    private static boolean isInPromptRange(String metricType, BigDecimal value) {
        double[] range =
                switch (metricType) {
                    case "BLOOD_GLUCOSE" -> new double[] {1, 35};
                    case "BLOOD_PRESSURE_SYS" -> new double[] {60, 260};
                    case "BLOOD_PRESSURE_DIA" -> new double[] {30, 160};
                    case "HEART_RATE" -> new double[] {30, 220};
                    case "WEIGHT" -> new double[] {30, 250};
                    case "HEIGHT" -> new double[] {100, 230};
                    case "WAIST" -> new double[] {50, 180};
                    case "TEMPERATURE" -> new double[] {34, 43};
                    case "SLEEP_HOURS" -> new double[] {0.5, 24};
                    case "STEPS" -> new double[] {1, 100000};
                    default -> null;
                };
        if (range == null) {
            return VitalValueRules.isPlausible(metricType, value);
        }
        double v = value.doubleValue();
        return !Double.isNaN(v) && !Double.isInfinite(v) && v >= range[0] && v <= range[1];
    }

    private static Hit hit(String field, String metricType, String label, String reason) {
        return new Hit(field, metricType, label, reason);
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String t = n.asText("").trim();
        if (!StringUtils.hasText(t)) {
            return null;
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
