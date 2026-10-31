package com.healix.core.goal.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.exception.BusinessException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 减重目标模式与校验（方案 §8.2）。
 *
 * <ul>
 *   <li>BMI ≥ 24 → 默认 LOSS；速度 &gt; 约 1 kg/周 → WARN
 *   <li>18.5 ≤ BMI &lt; 24 → 默认 BODY_COMPOSITION；设下降目标 → WARN；目标 BMI &lt; 18.5 → ERROR
 *   <li>BMI &lt; 18.5 → 不设减重；LOSS → ERROR
 *   <li>BLOCK 高亮 → 不设减重；LOSS → ERROR
 * </ul>
 */
public final class HealthGoalRules {

    public static final String MODE_LOSS = "LOSS";
    public static final String MODE_BODY_COMPOSITION = "BODY_COMPOSITION";
    public static final String MODE_MAINTAIN = "MAINTAIN";

    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_INFO = "INFO";

    private static final double BMI_LOSS = 24.0;
    private static final double BMI_UNDER = 18.5;
    private static final double MAX_KG_PER_WEEK = 1.0;

    private HealthGoalRules() {}

    public record Issue(String level, String code, String message) {}

    public record Decision(String mode, List<Issue> issues) {}

    /** 按 BMI 与是否阻断给出默认模式（不覆盖已有合法模式，仅建议）。 */
    public static String defaultMode(Double bmi, boolean lossBlocked) {
        if (lossBlocked) {
            return MODE_MAINTAIN;
        }
        if (bmi == null) {
            return MODE_LOSS;
        }
        if (bmi < BMI_UNDER) {
            return MODE_MAINTAIN;
        }
        if (bmi < BMI_LOSS) {
            return MODE_BODY_COMPOSITION;
        }
        return MODE_LOSS;
    }

    /**
     * @param goalsJson 目标数组节点（可为 null）
     * @param heightCm 用于目标 BMI 校验；可空
     */
    public static Decision validate(
            String goalMode, Double bmi, boolean lossBlocked, JsonNode goalsJson, Double heightCm) {
        List<Issue> issues = new ArrayList<>();
        String resolved = normalizeMode(goalMode);
        if (resolved == null) {
            resolved = defaultMode(bmi, lossBlocked);
            issues.add(new Issue(LEVEL_INFO, "GOAL_MODE_DEFAULT", "已按 BMI/高亮建议目标模式：" + resolved));
        }
        final String mode = resolved;

        if (lossBlocked && MODE_LOSS.equals(mode)) {
            issues.add(new Issue(LEVEL_ERROR, "GOAL_LOSS_BLOCKED", "存在阻断级高亮（如孕哺），不能设减重目标"));
        }
        if (bmi != null && bmi < BMI_UNDER) {
            issues.add(new Issue(LEVEL_WARN, "GOAL_UNDERWEIGHT", "BMI < 18.5，建议转介，不设减重目标"));
            if (MODE_LOSS.equals(mode)) {
                issues.add(new Issue(LEVEL_ERROR, "GOAL_LOSS_UNDERWEIGHT", "偏瘦人群不能设减重目标"));
            }
        }
        if (bmi != null && bmi >= BMI_UNDER && bmi < BMI_LOSS && MODE_LOSS.equals(mode)) {
            issues.add(new Issue(LEVEL_WARN, "GOAL_LOSS_NORMAL_BMI", "BMI 未达超重，设体重下降目标请谨慎"));
        }

        if (goalsJson != null && !goalsJson.isNull()) {
            if (goalsJson.isArray()) {
                for (JsonNode item : goalsJson) {
                    issues.addAll(validateItem(item, mode, heightCm));
                }
            } else if (goalsJson.isObject()) {
                goalsJson.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && e.getValue().isObject()) {
                        com.fasterxml.jackson.databind.node.ObjectNode item =
                                (com.fasterxml.jackson.databind.node.ObjectNode) e.getValue().deepCopy();
                        if (!item.has("metric")) {
                            item.put("metric", e.getKey());
                        }
                        issues.addAll(validateItem(item, mode, heightCm));
                    }
                });
            }
        }

        return new Decision(mode, List.copyOf(issues));
    }

    /** 有 ERROR 则抛业务异常。 */
    public static Decision requireValid(
            String goalMode, Double bmi, boolean lossBlocked, JsonNode goalsJson, Double heightCm) {
        Decision d = validate(goalMode, bmi, lossBlocked, goalsJson, heightCm);
        List<String> errors = d.issues().stream()
                .filter(i -> LEVEL_ERROR.equals(i.level()))
                .map(Issue::message)
                .toList();
        if (!errors.isEmpty()) {
            throw new BusinessException(400, "目标不合法：" + String.join("；", errors));
        }
        return d;
    }

    private static List<Issue> validateItem(JsonNode item, String mode, Double heightCm) {
        List<Issue> out = new ArrayList<>();
        String metric = item.path("metric").asText("").trim().toUpperCase(Locale.ROOT);
        if (!"WEIGHT".equals(metric)) {
            return out;
        }
        Double baseline = num(item, "baseline");
        Double target = num(item, "target");
        if (baseline == null || target == null) {
            return out;
        }
        double delta = baseline - target;
        if (delta > 0 && !MODE_LOSS.equals(mode) && !MODE_BODY_COMPOSITION.equals(mode)) {
            out.add(new Issue(LEVEL_WARN, "GOAL_WEIGHT_DROP", "目标体重低于基线，但模式不是减重/体成分"));
        }
        if (delta > 0 && MODE_MAINTAIN.equals(mode)) {
            out.add(new Issue(LEVEL_ERROR, "GOAL_MAINTAIN_DROP", "维持模式不能设体重下降目标"));
        }
        LocalDate deadline = date(item.path("deadline").asText(null));
        if (delta > 0 && deadline != null) {
            long days = ChronoUnit.DAYS.between(LocalDate.now(), deadline);
            if (days > 0) {
                double perWeek = delta / (days / 7.0);
                if (perWeek > MAX_KG_PER_WEEK) {
                    out.add(new Issue(
                            LEVEL_WARN,
                            "GOAL_PACE_FAST",
                            String.format(Locale.ROOT, "平均速度约 %.1f kg/周，超过约 1 kg/周", perWeek)));
                }
            }
        }
        if (heightCm != null && heightCm > 0 && target > 0) {
            double meters = heightCm / 100.0;
            double targetBmi = target / (meters * meters);
            if (targetBmi < BMI_UNDER) {
                out.add(new Issue(LEVEL_ERROR, "GOAL_TARGET_BMI_LOW", "目标体重对应 BMI < 18.5"));
            }
        }
        return out;
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        String m = mode.trim().toUpperCase(Locale.ROOT);
        return Set.of(MODE_LOSS, MODE_BODY_COMPOSITION, MODE_MAINTAIN).contains(m) ? m : null;
    }

    private static Double num(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v != null && v.isNumber() ? v.asDouble() : null;
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim().substring(0, Math.min(10, raw.trim().length())));
        } catch (Exception e) {
            return null;
        }
    }
}
