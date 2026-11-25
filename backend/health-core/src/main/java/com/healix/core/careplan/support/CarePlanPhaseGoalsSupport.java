package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/** 方案阶段目标（phaseGoals）规范化与一句话摘要。 */
public final class CarePlanPhaseGoalsSupport {

    public static final String METRIC_WEIGHT = "WEIGHT";
    public static final String METRIC_BP = "BP";
    public static final String METRIC_FPG = "FPG";
    public static final String METRIC_HBA1C = "HBA1C";
    public static final String METRIC_WAIST = "WAIST";
    public static final String METRIC_HABIT = "HABIT_ADHERENCE";

    public static final String DISPLAY_TARGET = "TARGET";
    public static final String DISPLAY_RANGE = "RANGE";

    public static final String SOURCE_INTAKE = "INTAKE";
    public static final String SOURCE_GUIDELINE = "GUIDELINE_DEFAULT";
    public static final String SOURCE_LLM = "LLM";
    public static final String SOURCE_STAFF = "STAFF";

    private static final Set<String> METRICS = Set.of(
            METRIC_WEIGHT, METRIC_BP, METRIC_FPG, METRIC_HBA1C, METRIC_WAIST, METRIC_HABIT);

    private CarePlanPhaseGoalsSupport() {}

    /** 清洗并保证 primaryMetric / items 结构；非法输入返回空对象。 */
    public static ObjectNode normalize(JsonNode raw) {
        ObjectNode out = JsonUtils.emptyObject();
        if (raw == null || raw.isNull() || !raw.isObject()) {
            out.putArray("items");
            return out;
        }
        ObjectNode src = (ObjectNode) raw;
        int horizon = src.path("horizonDays").asInt(0);
        if (horizon > 0) {
            out.put("horizonDays", horizon);
        }
        String horizonLabel = text(src, "horizonLabel");
        if (StringUtils.hasText(horizonLabel)) {
            out.put("horizonLabel", horizonLabel.trim());
        }
        String primary = normalizeMetric(text(src, "primaryMetric"));
        ArrayNode itemsOut = out.putArray("items");
        JsonNode items = src.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode n : items) {
                ObjectNode item = normalizeItem(n);
                if (item != null) {
                    itemsOut.add(item);
                }
            }
        }
        if (!StringUtils.hasText(primary) && !itemsOut.isEmpty()) {
            primary = itemsOut.get(0).path("metric").asText(null);
        }
        if (StringUtils.hasText(primary)) {
            out.put("primaryMetric", primary);
        }
        return out;
    }

    public static ObjectNode findPrimaryItem(JsonNode phaseGoals) {
        if (phaseGoals == null || !phaseGoals.isObject()) {
            return null;
        }
        String primary = normalizeMetric(phaseGoals.path("primaryMetric").asText(null));
        JsonNode items = phaseGoals.get("items");
        if (items == null || !items.isArray()) {
            return null;
        }
        ObjectNode first = null;
        for (JsonNode n : items) {
            if (n == null || !n.isObject()) {
                continue;
            }
            ObjectNode item = (ObjectNode) n;
            if (first == null) {
                first = item;
            }
            if (StringUtils.hasText(primary) && primary.equalsIgnoreCase(item.path("metric").asText(""))) {
                return item;
            }
        }
        return first;
    }

    /** 主指标是否有可落地的数值目标（足以覆盖 LLM 自由发挥的 goalSummary）。 */
    public static boolean hasConcreteOutcomeTarget(JsonNode phaseGoals) {
        ObjectNode primary = findPrimaryItem(phaseGoals);
        if (primary == null) {
            return false;
        }
        String metric = normalizeMetric(primary.path("metric").asText(null));
        if (!StringUtils.hasText(metric) || METRIC_HABIT.equals(metric)) {
            return false;
        }
        return switch (metric) {
            case METRIC_WEIGHT ->
                    asDouble(primary.get("target")) != null
                            || (asDouble(primary.get("min")) != null && asDouble(primary.get("max")) != null);
            case METRIC_BP -> asInt(primary.get("sysMax")) != null && asInt(primary.get("diaMax")) != null;
            case METRIC_FPG, METRIC_HBA1C ->
                    asDouble(primary.get("max")) != null
                            || asDouble(primary.get("target")) != null
                            || (asDouble(primary.get("min")) != null && asDouble(primary.get("max")) != null);
            case METRIC_WAIST -> asDouble(primary.get("target")) != null;
            default -> false;
        };
    }

    /**
     * 由结构化目标拼一句话 goalSummary（主指标 + 辅项）。
     * 有具体结局数字时，应用此结果覆盖 LLM 自由撰写的目标，避免另编体重/血压/血糖。
     */
    public static String composeGoalSummary(JsonNode phaseGoals) {
        if (isEmpty(phaseGoals)) {
            return null;
        }
        String horizonLabel = text(phaseGoals, "horizonLabel");
        String primaryMetric = normalizeMetric(text(phaseGoals, "primaryMetric"));
        java.util.List<String> parts = new java.util.ArrayList<>();
        String primaryPart = formatItemClause(findPrimaryItem(phaseGoals), true);
        if (StringUtils.hasText(primaryPart)) {
            parts.add(primaryPart);
        }
        JsonNode items = phaseGoals.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode n : items) {
                if (n == null || !n.isObject()) {
                    continue;
                }
                String metric = normalizeMetric(n.path("metric").asText(null));
                if (!StringUtils.hasText(metric) || metric.equals(primaryMetric)) {
                    continue;
                }
                String clause = formatItemClause((ObjectNode) n, false);
                if (StringUtils.hasText(clause)) {
                    parts.add(clause);
                }
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        String body = String.join("，", parts);
        if (StringUtils.hasText(horizonLabel)) {
            return horizonLabel.trim() + "内" + body;
        }
        return body;
    }

    /** 由结构化目标生成一句话 goalSummary；无可用数字时返回 null。 */
    public static String deriveGoalSummary(JsonNode phaseGoals) {
        return composeGoalSummary(phaseGoals);
    }

    private static String formatItemClause(ObjectNode item, boolean withPrefixContext) {
        if (item == null) {
            return null;
        }
        String metric = normalizeMetric(item.path("metric").asText(null));
        if (!StringUtils.hasText(metric)) {
            return null;
        }
        return switch (metric) {
            case METRIC_WEIGHT -> {
                String display = item.path("display").asText(DISPLAY_TARGET);
                if (DISPLAY_RANGE.equalsIgnoreCase(display)) {
                    Double min = asDouble(item.get("min"));
                    Double max = asDouble(item.get("max"));
                    if (min != null && max != null) {
                        yield "体重稳住在 " + fmt(min) + "–" + fmt(max) + " kg";
                    }
                    yield "体重维持在健康区间";
                }
                Double target = asDouble(item.get("target"));
                Double baseline = asDouble(item.get("baseline"));
                if (target != null && baseline != null && target < baseline - 0.05) {
                    yield "体重从 " + fmt(baseline) + " kg 减至 " + fmt(target) + " kg";
                }
                if (target != null && baseline != null && target > baseline + 0.05) {
                    // 目标高于基线：不做「减重」叙事，如实写目标体重（首诊填了多少用多少）
                    yield "体重目标 " + fmt(target) + " kg（当前 " + fmt(baseline) + " kg）";
                }
                if (target != null) {
                    yield "体重目标 " + fmt(target) + " kg";
                }
                yield null;
            }
            case METRIC_BP -> {
                Integer sysMax = asInt(item.get("sysMax"));
                Integer diaMax = asInt(item.get("diaMax"));
                if (sysMax != null && diaMax != null) {
                    yield "家庭血压控制在 <" + sysMax + "/" + diaMax + " mmHg";
                }
                yield null;
            }
            case METRIC_FPG -> {
                Double min = asDouble(item.get("min"));
                Double max = asDouble(item.get("max"));
                if (min != null && max != null) {
                    yield "空腹血糖 " + fmt(min) + "–" + fmt(max) + " mmol/L";
                }
                Double target = asDouble(item.get("target"));
                if (target != null) {
                    yield "空腹血糖目标 " + fmt(target) + " mmol/L";
                }
                yield null;
            }
            case METRIC_HBA1C -> {
                Double max = asDouble(item.get("max"));
                Double target = asDouble(item.get("target"));
                if (max != null) {
                    yield "糖化血红蛋白 <" + fmt(max) + "%";
                }
                if (target != null) {
                    yield "糖化血红蛋白目标 " + fmt(target) + "%";
                }
                yield null;
            }
            case METRIC_WAIST -> {
                Double target = asDouble(item.get("target"));
                if (target != null) {
                    yield "腰围目标 " + fmt(target) + " cm";
                }
                yield null;
            }
            case METRIC_HABIT -> {
                Double minPct = asDouble(item.get("min"));
                if (minPct != null) {
                    yield "本周任务完成率 ≥" + fmt(minPct) + "%";
                }
                yield withPrefixContext ? "按方案坚持完成每日任务" : null;
            }
            default -> null;
        };
    }

    public static boolean isEmpty(JsonNode phaseGoals) {
        if (phaseGoals == null || phaseGoals.isNull() || !phaseGoals.isObject()) {
            return true;
        }
        JsonNode items = phaseGoals.get("items");
        return items == null || !items.isArray() || items.isEmpty();
    }

    public static String normalizeMetric(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if ("BLOOD_PRESSURE".equals(u) || "BP_SYS".equals(u) || "BP_DIA".equals(u)) {
            return METRIC_BP;
        }
        if ("GLUCOSE".equals(u) || "BLOOD_GLUCOSE".equals(u) || "FASTING_GLUCOSE".equals(u)) {
            return METRIC_FPG;
        }
        if ("HABIT".equals(u) || "ADHERENCE".equals(u)) {
            return METRIC_HABIT;
        }
        return METRICS.contains(u) ? u : null;
    }

    private static ObjectNode normalizeItem(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        String metric = normalizeMetric(n.path("metric").asText(null));
        if (!StringUtils.hasText(metric)) {
            return null;
        }
        ObjectNode item = JsonUtils.emptyObject();
        item.put("metric", metric);
        String display = n.path("display").asText(null);
        if (!StringUtils.hasText(display)) {
            display = METRIC_BP.equals(metric) || METRIC_FPG.equals(metric) || METRIC_HABIT.equals(metric)
                    ? DISPLAY_RANGE
                    : DISPLAY_TARGET;
        }
        item.put("display", display.trim().toUpperCase(Locale.ROOT));
        String unit = text(n, "unit");
        if (StringUtils.hasText(unit)) {
            item.put("unit", unit.trim());
        } else {
            String defUnit = defaultUnit(metric);
            if (defUnit != null) {
                item.put("unit", defUnit);
            }
        }
        copyNumber(n, item, "baseline");
        copyNumber(n, item, "target");
        copyNumber(n, item, "min");
        copyNumber(n, item, "max");
        copyInt(n, item, "sysMax");
        copyInt(n, item, "diaMax");
        copyInt(n, item, "sysMin");
        copyInt(n, item, "diaMin");
        String deadline = text(n, "deadline");
        if (StringUtils.hasText(deadline)) {
            item.put("deadline", deadline.trim());
        }
        String horizon = text(n, "horizon");
        if (StringUtils.hasText(horizon)) {
            item.put("horizon", horizon.trim());
        }
        String source = text(n, "source");
        if (StringUtils.hasText(source)) {
            item.put("source", source.trim().toUpperCase(Locale.ROOT));
        }
        return item;
    }

    private static String defaultUnit(String metric) {
        return switch (metric) {
            case METRIC_WEIGHT -> "kg";
            case METRIC_BP -> "mmHg";
            case METRIC_FPG -> "mmol/L";
            case METRIC_HBA1C -> "%";
            case METRIC_WAIST -> "cm";
            case METRIC_HABIT -> "%";
            default -> null;
        };
    }

    private static void copyNumber(JsonNode src, ObjectNode dest, String field) {
        Double v = asDouble(src.get(field));
        if (v != null) {
            dest.put(field, v);
        }
    }

    private static void copyInt(JsonNode src, ObjectNode dest, String field) {
        Integer v = asInt(src.get(field));
        if (v != null) {
            dest.put(field, v);
        }
    }

    static Double asDouble(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.asDouble();
        }
        if (n.isTextual()) {
            try {
                return Double.parseDouble(n.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    static Integer asInt(JsonNode n) {
        Double d = asDouble(n);
        return d == null ? null : (int) Math.round(d);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        return v.asText();
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.05) {
            return String.valueOf((long) Math.rint(v));
        }
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
