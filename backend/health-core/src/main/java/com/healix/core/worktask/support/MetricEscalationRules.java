package com.healix.core.worktask.support;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 指标异常单（METRIC_ALERT）内升级展示的条件。
 *
 * <p>单次轻度异常只留常规摘要。同一指标族凑满 {@link #REPEAT_HITS} 次，
 * 或血压达到 3 级（收缩压 ≥180 或舒张压 ≥110，与 {@code HypertensionRiskRules} 一致），
 * 或血糖 ≥13.9 / &lt;4.0 mmol/L（与糖尿病严重度里的高血糖、低血糖线一致），
 * 才在异常单上抬优先级并标注升级原因。心率和 BMI 只按重复次数升级。
 * 不另开随访任务。
 */
public final class MetricEscalationRules {

    public static final int REPEAT_HITS = 2;
    public static final double BP_SYS_GRADE3 = 180d;
    public static final double BP_DIA_GRADE3 = 110d;
    public static final double GLUCOSE_HIGH_MMOL = 13.9d;
    public static final double GLUCOSE_HYPO_MMOL = 4.0d;

    private MetricEscalationRules() {}

    public static boolean shouldEscalate(List<Map<String, Object>> hits) {
        return escalateReason(hits) != null;
    }

    /**
     * @return 升级原因文案；未达升级条件时为 null
     */
    public static String escalateReason(List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        Map<String, Integer> byFamily = new HashMap<>();
        boolean dangerousHit = false;
        String dangerousSummary = null;
        for (Map<String, Object> hit : hits) {
            if (hit == null) {
                continue;
            }
            if (dangerous(hit)) {
                dangerousHit = true;
                Object summary = hit.get("summary");
                if (dangerousSummary == null && summary != null && String.valueOf(summary).trim().length() > 0) {
                    dangerousSummary = String.valueOf(summary).trim();
                }
            }
            String family = hit.get("family") == null ? "" : String.valueOf(hit.get("family"));
            byFamily.merge(family, 1, Integer::sum);
        }
        if (dangerousHit) {
            return dangerousSummary != null ? dangerousSummary : "危险值需优先处理";
        }
        boolean repeat = false;
        for (int count : byFamily.values()) {
            if (count >= REPEAT_HITS) {
                repeat = true;
                break;
            }
        }
        if (!repeat) {
            return null;
        }
        long families = byFamily.keySet().stream().filter(f -> !f.isEmpty()).count();
        if (families == 0) {
            families = byFamily.size();
        }
        return families > 1 ? "多项指标重复异常" : "同一指标再次异常";
    }

    public static boolean dangerous(Map<String, Object> hit) {
        if (hit == null) {
            return false;
        }
        String family = hit.get("family") == null ? "" : String.valueOf(hit.get("family"));
        if ("BP".equals(family)) {
            Double sys = num(hit.get("sys"));
            Double dia = num(hit.get("dia"));
            return (sys != null && sys >= BP_SYS_GRADE3) || (dia != null && dia >= BP_DIA_GRADE3);
        }
        if ("GLUCOSE".equals(family)) {
            Double value = num(hit.get("value"));
            return value != null && (value >= GLUCOSE_HIGH_MMOL || value < GLUCOSE_HYPO_MMOL);
        }
        return false;
    }

    private static Double num(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
