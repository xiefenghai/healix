package com.healix.core.careplan.guideline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.dto.SafetyFlagDto;
import com.healix.core.careplan.enums.CarePlanSafetyLevelEnum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方案对照条目库的引用与量化校验。
 *
 * <p>量化校验对所有 matched 条目执行，不论方案是否引用、也不论内容来自 AI、模板还是手工编辑；
 * 安全类越界为 ERROR，健管师指令不能绕过。
 */
public final class GuidelineCheck {

    public static final String ERR_SAFETY = "GUIDELINE_SAFETY_VIOLATION";
    public static final String ERR_REF_EXCLUDED = "GUIDELINE_REF_EXCLUDED";
    public static final String WARN_TARGET = "GUIDELINE_TARGET_CONFLICT";
    public static final String WARN_REF_UNKNOWN = "GUIDELINE_REF_UNKNOWN";
    public static final String WARN_REF_MISSING = "GUIDELINE_REF_MISSING";
    public static final String WARN_KB_EMPTY = "GUIDELINE_KB_EMPTY";

    public static final String REFS = "refs";

    private GuidelineCheck() {}

    /**
     * 剔除不在 matched / excluded 中的引用（原地修改），并返回校验结果。
     *
     * @param requireRefs 生成稿为 true：有适用条目的块缺引用时提示
     */
    public static List<SafetyFlagDto> apply(
            JsonNode exercise, JsonNode diet, JsonNode execution, GuidelineMatch match, boolean requireRefs) {
        if (match == null || !match.enabled()) {
            return List.of();
        }
        List<SafetyFlagDto> flags = new ArrayList<>();
        if (match.matched().isEmpty()) {
            flags.add(warn(WARN_KB_EMPTY, "本方案关键建议无指南条目覆盖，请健管师自行把关"));
            return flags;
        }
        Set<String> allowed = match.matchedCodes();
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> excludedRefs = new LinkedHashSet<>();
        Map<String, JsonNode> blocks = new LinkedHashMap<>();
        blocks.put("exercise", exercise);
        blocks.put("diet", diet);
        blocks.put("execution", execution);
        for (JsonNode block : blocks.values()) {
            sanitize(block, allowed, match, unknown, excludedRefs);
        }
        JsonNode tasks = execution == null ? null : execution.get("tasks");
        if (tasks != null && tasks.isArray()) {
            for (JsonNode t : tasks) {
                sanitize(t, allowed, match, unknown, excludedRefs);
            }
        }

        List<String> safety = new ArrayList<>();
        List<String> target = new ArrayList<>();
        for (int i = 0; i < match.matched().size(); i++) {
            GuidelineRec rec = match.matched().get(i);
            if (rec.quant() == null) {
                continue;
            }
            String violation = violation(rec.quant(), exercise, diet, execution);
            if (violation != null) {
                String msg = "「" + rec.quant().describe() + "」" + violation + "（" + match.sourceLabels().get(i) + "）";
                (rec.safety() ? safety : target).add(msg);
            }
        }
        if (!safety.isEmpty()) {
            flags.add(error(ERR_SAFETY, "超出指南安全要求，须修改后发布：" + String.join("；", safety)));
        }
        if (!excludedRefs.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (String code : excludedRefs) {
                match.excluded(code).ifPresent(e -> parts.add(code + "（" + e.reason() + "时不适用）"));
            }
            flags.add(error(ERR_REF_EXCLUDED, "引用了对该患者禁忌的条目：" + String.join("；", parts)));
        }
        if (!target.isEmpty()) {
            flags.add(warn(WARN_TARGET, "与指南推荐量不一致，请确认是否为个体化调整：" + String.join("；", target)));
        }
        if (!unknown.isEmpty()) {
            flags.add(warn(WARN_REF_UNKNOWN, "已移除不适用或不存在的依据引用：" + String.join("、", unknown)));
        }
        if (requireRefs) {
            List<String> missing = new ArrayList<>();
            blocks.forEach((name, block) -> {
                if (!match.forPlanBlock(name).isEmpty() && refsOf(block).isEmpty()) {
                    missing.add(blockLabel(name));
                }
            });
            if (!missing.isEmpty()) {
                flags.add(warn(WARN_REF_MISSING, String.join("、", missing) + "未标注指南依据，请审阅后确认"));
            }
        }
        return flags;
    }

    /** 模板兜底用：按块写入全部适用条目，监测任务按关联指标挂引用。 */
    public static void attachTemplateRefs(
            ObjectNode exercise, ObjectNode diet, ObjectNode execution, GuidelineMatch match) {
        if (match == null || !match.enabled()) {
            return;
        }
        putRefs(exercise, match.forPlanBlock("exercise"));
        putRefs(diet, match.forPlanBlock("diet"));
        putRefs(execution, match.forPlanBlock("execution"));
        JsonNode tasks = execution == null ? null : execution.get("tasks");
        if (tasks == null || !tasks.isArray()) {
            return;
        }
        for (JsonNode t : tasks) {
            String ref = t.path("relatedRef").asText("");
            boolean water = "WATER_LOG".equals(t.path("code").asText(""));
            List<GuidelineRec> hits = match.forPlanBlock("execution").stream()
                    .filter(r -> r.quant() != null
                            && (GuidelineQuant.MONITOR_TASK_PRESENT.equals(r.quant().metric())
                                            && ref.equals(r.quant().relatedRef())
                                    || water && GuidelineQuant.WATER_ML_RANGE.equals(r.quant().metric())))
                    .toList();
            if (!hits.isEmpty() && t instanceof ObjectNode obj) {
                putRefs(obj, hits);
            }
        }
    }

    public static Set<String> refsOf(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode refs = node == null ? null : node.get(REFS);
        if (refs != null && refs.isArray()) {
            refs.forEach(r -> {
                if (r.isTextual() && !r.asText().isBlank()) {
                    out.add(r.asText().trim());
                }
            });
        }
        return out;
    }

    static String violation(GuidelineQuant q, JsonNode exercise, JsonNode diet, JsonNode execution) {
        switch (q.metric()) {
            case GuidelineQuant.WEEKLY_MODERATE_MIN -> {
                double minutes = weeklyModerateMinutes(exercise);
                boolean bad = ">=".equals(q.op()) ? minutes < q.value() : minutes > q.value();
                return bad ? "方案每周约 " + GuidelineQuant.fmt(minutes) + " 分钟" : null;
            }
            case GuidelineQuant.SESSION_MAX_MIN -> {
                double max = 0;
                for (JsonNode item : exerciseItems(exercise)) {
                    max = Math.max(max, item.path("durationMin").asDouble(0));
                }
                return max > q.value() ? "方案单次最长 " + GuidelineQuant.fmt(max) + " 分钟" : null;
            }
            case GuidelineQuant.INTENSITY_MAX -> {
                int limit = GuidelineQuant.intensityRank(q.level());
                String worst = null;
                for (JsonNode item : exerciseItems(exercise)) {
                    String level = item.path("intensity").asText(null);
                    if (GuidelineQuant.intensityRank(level) > limit
                            && (worst == null || GuidelineQuant.intensityRank(level) > GuidelineQuant.intensityRank(worst))) {
                        worst = level.trim().toUpperCase(java.util.Locale.ROOT);
                    }
                }
                return worst == null ? null : "方案含" + GuidelineQuant.intensityLabel(worst) + "运动";
            }
            case GuidelineQuant.MONITOR_TASK_PRESENT -> {
                for (JsonNode t : enabledTasks(execution)) {
                    if (q.relatedRef().equals(t.path("relatedRef").asText(""))) {
                        return null;
                    }
                }
                return "执行计划未安排该监测任务";
            }
            case GuidelineQuant.WATER_ML_RANGE -> {
                for (JsonNode t : enabledTasks(execution)) {
                    if ("WATER_LOG".equals(t.path("code").asText("")) || "WATER".equals(t.path("category").asText(""))) {
                        double ml = t.path("targetMl").asDouble(0);
                        if (ml > 0 && (ml < q.min() || ml > q.max())) {
                            return "方案饮水目标 " + GuidelineQuant.fmt(ml) + " ml";
                        }
                    }
                }
                return null;
            }
            case GuidelineQuant.ENERGY_DEFICIT_KCAL -> {
                Double actual = firstNumber(diet, "energyDeficitKcal", "calorieDeficit");
                if (actual == null) {
                    return null;
                }
                boolean bad = ">=".equals(q.op()) ? actual < q.value() : actual > q.value();
                return bad ? "方案缺口 " + GuidelineQuant.fmt(actual) + " kcal" : null;
            }
            case GuidelineQuant.WEIGHT_LOSS_RATE_KG_PER_WEEK -> {
                Double actual = firstNumber(diet, "weightLossRateKgPerWeek");
                if (actual == null && diet != null && diet.has("goals")) {
                    actual = firstNumber(diet.get("goals"), "paceKgPerWeek");
                }
                if (actual == null) {
                    return null;
                }
                boolean bad = ">=".equals(q.op()) ? actual < q.value() : actual > q.value();
                return bad ? "方案速度 " + GuidelineQuant.fmt(actual) + " kg/周" : null;
            }
            case GuidelineQuant.WEIGH_FREQUENCY -> {
                // value = 每周最多次数；任务 freq 如 DAILY≈7、WEEKLY≈1
                Double maxPerWeek = null;
                for (JsonNode t : enabledTasks(execution)) {
                    if (!"WEIGHT".equals(t.path("relatedRef").asText(""))
                            && !"WEIGHT".equals(t.path("metricType").asText(""))
                            && !"WEIGH".equals(t.path("category").asText(""))) {
                        continue;
                    }
                    double perWeek = weighPerWeek(t.path("freq").asText(t.path("frequency").asText("")));
                    if (perWeek > 0) {
                        maxPerWeek = maxPerWeek == null ? perWeek : Math.max(maxPerWeek, perWeek);
                    }
                }
                if (maxPerWeek == null) {
                    return null;
                }
                boolean bad = ">=".equals(q.op()) ? maxPerWeek < q.value() : maxPerWeek > q.value();
                return bad ? "方案称重约每周 " + GuidelineQuant.fmt(maxPerWeek) + " 次" : null;
            }
            default -> {
                return null;
            }
        }
    }

    private static Double firstNumber(JsonNode node, String... fields) {
        if (node == null || node.isNull()) {
            return null;
        }
        for (String f : fields) {
            JsonNode v = node.get(f);
            if (v != null && v.isNumber()) {
                return v.asDouble();
            }
        }
        return null;
    }

    private static double weighPerWeek(String freq) {
        if (freq == null || freq.isBlank()) {
            return 0;
        }
        return switch (freq.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "DAILY", "EVERY_DAY" -> 7;
            case "WEEKLY", "EVERY_WEEK" -> 1;
            case "BIWEEKLY", "EVERY_2_WEEKS" -> 0.5;
            default -> 0;
        };
    }

    /** 中等强度分钟数；高强度按 2 倍折算，低强度不计。 */
    static double weeklyModerateMinutes(JsonNode exercise) {
        double total = 0;
        for (JsonNode item : exerciseItems(exercise)) {
            String level = item.path("intensity").asText("").trim().toUpperCase(java.util.Locale.ROOT);
            double min = item.path("durationMin").asDouble(0);
            if ("MODERATE".equals(level)) {
                total += min;
            } else if ("VIGOROUS".equals(level)) {
                total += min * 2;
            }
        }
        return total;
    }

    private static List<JsonNode> exerciseItems(JsonNode exercise) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode weekly = exercise == null ? null : exercise.get("weeklyPlan");
        if (weekly == null || !weekly.isArray()) {
            return out;
        }
        for (JsonNode day : weekly) {
            for (JsonNode item : day.path("items")) {
                out.add(item);
            }
        }
        return out;
    }

    private static List<JsonNode> enabledTasks(JsonNode execution) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode tasks = execution == null ? null : execution.get("tasks");
        if (tasks != null && tasks.isArray()) {
            for (JsonNode t : tasks) {
                if (t.path("enabled").asBoolean(true)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static void sanitize(
            JsonNode node, Set<String> allowed, GuidelineMatch match, Set<String> unknown, Set<String> excludedRefs) {
        if (!(node instanceof ObjectNode obj) || !obj.has(REFS)) {
            return;
        }
        ArrayNode kept = JsonUtils.mapper().createArrayNode();
        for (String code : refsOf(obj)) {
            if (allowed.contains(code)) {
                kept.add(code);
            } else if (match.excluded(code).isPresent()) {
                kept.add(code);
                excludedRefs.add(code);
            } else {
                unknown.add(code);
            }
        }
        if (kept.isEmpty()) {
            obj.remove(REFS);
        } else {
            obj.set(REFS, kept);
        }
    }

    private static void putRefs(ObjectNode node, List<GuidelineRec> recs) {
        if (node == null || recs.isEmpty()) {
            return;
        }
        ArrayNode arr = node.putArray(REFS);
        recs.forEach(r -> arr.add(r.code()));
    }

    private static String blockLabel(String name) {
        return switch (name) {
            case "exercise" -> "运动方案";
            case "diet" -> "饮食方案";
            default -> "执行计划";
        };
    }

    private static SafetyFlagDto error(String code, String msg) {
        return new SafetyFlagDto(CarePlanSafetyLevelEnum.ERROR.name(), code, msg);
    }

    private static SafetyFlagDto warn(String code, String msg) {
        return new SafetyFlagDto(CarePlanSafetyLevelEnum.WARN.name(), code, msg);
    }
}
