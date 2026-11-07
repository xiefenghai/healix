package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.water.support.WaterIntakeRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 执行任务清单硬约束：饮食记录与服药提醒拆分、饮水目标与备注对齐体重、久坐微习惯频次语义。
 */
public final class CarePlanExecutionTaskNormalizer {

    public static final String MED_REMINDER_CODE = "MED_REMINDER";
    public static final String MED_CATEGORY = "MEDICATION";
    public static final String HABIT_FREQUENCY = "HABIT";

    private CarePlanExecutionTaskNormalizer() {}

    public static void normalize(JsonNode execution, JsonNode contextSnapshot) {
        if (!(execution instanceof ObjectNode exec)) {
            return;
        }
        JsonNode tasksNode = exec.get("tasks");
        if (!(tasksNode instanceof ArrayNode tasks) || tasks.isEmpty()) {
            return;
        }
        splitDietAndMedication(tasks, contextSnapshot);
        ensureMealMedicationReminder(tasks, contextSnapshot);
        normalizeSedentaryBreaks(tasks);
        normalizeWaterTasks(tasks, contextSnapshot);
    }

    /** 饮食任务标题里夹带服药 → 拆成纯饮食 + 用药提醒。 */
    static void splitDietAndMedication(ArrayNode tasks, JsonNode contextSnapshot) {
        List<ObjectNode> toAdd = new ArrayList<>();
        for (JsonNode n : tasks) {
            if (!(n instanceof ObjectNode task)) {
                continue;
            }
            if (!isDietLogTask(task)) {
                continue;
            }
            String title = task.path("title").asText("").trim();
            if (!containsMedicationCue(title)) {
                continue;
            }
            String medPart = extractMedicationPart(title);
            task.put("title", "记录三餐与加餐");
            String rationale = task.path("rationale").asText("").trim();
            if (containsMedicationCue(rationale)) {
                task.put("rationale", "便于回顾饮食并对照示例日");
            }
            if (!hasMedReminder(tasks) && !toAdd.stream().anyMatch(CarePlanExecutionTaskNormalizer::isMedReminder)) {
                toAdd.add(buildMedReminder(medPart, contextSnapshot));
            }
        }
        for (ObjectNode add : toAdd) {
            tasks.add(add);
        }
    }

    /** 有随餐药但无 MED_REMINDER 时补一条。 */
    static void ensureMealMedicationReminder(ArrayNode tasks, JsonNode contextSnapshot) {
        if (hasMedReminder(tasks)) {
            return;
        }
        MealMedHint hint = mealMedicationHint(contextSnapshot);
        if (hint == null) {
            return;
        }
        tasks.add(buildMedReminder(hint.titleSuffix(), contextSnapshot));
    }

    static void normalizeSedentaryBreaks(ArrayNode tasks) {
        for (JsonNode n : tasks) {
            if (!(n instanceof ObjectNode task) || !isSedentaryBreak(task)) {
                continue;
            }
            task.put("frequency", HABIT_FREQUENCY);
            task.put("timeSlot", "ALL");
            if (!StringUtils.hasText(task.path("relatedRef").asText(null))) {
                task.put("relatedRef", "SEDENTARY_BREAK");
            }
            String rationale = task.path("rationale").asText("").trim();
            if (!StringUtils.hasText(rationale)
                    || rationale.contains("每日1次")
                    || rationale.contains("每天一次")) {
                task.put(
                        "rationale",
                        "全天穿插的微习惯：工作时每小时起身活动 3～5 分钟；当日有在做即可勾选一次，勿按「每日1次」理解成只动一次");
            }
        }
    }

    static void normalizeWaterTasks(ArrayNode tasks, JsonNode contextSnapshot) {
        Double weightKg = weightKg(contextSnapshot);
        Integer suggested = weightKg == null ? null : WaterIntakeRules.suggestTargetMl(weightKg);
        for (JsonNode n : tasks) {
            if (!(n instanceof ObjectNode task) || !isWaterTask(task)) {
                continue;
            }
            int targetMl = currentTargetMl(task);
            boolean staleClamp =
                    suggested != null
                            && suggested > WaterIntakeRules.LEGACY_MAX_TARGET_ML
                            && targetMl > 0
                            && targetMl <= WaterIntakeRules.LEGACY_MAX_TARGET_ML;
            if (staleClamp || targetMl <= 0) {
                targetMl = suggested != null ? suggested : WaterIntakeRules.DEFAULT_TARGET_ML;
                task.put("targetMl", targetMl);
                ObjectNode payload =
                        task.has("payload") && task.get("payload").isObject()
                                ? ((ObjectNode) task.get("payload")).deepCopy()
                                : JsonUtils.emptyObject();
                payload.put("targetMl", targetMl);
                task.set("payload", payload);
            }
            String title = task.path("title").asText("").trim();
            if (!StringUtils.hasText(title)
                    || title.contains("足量饮水")
                    || title.matches(".*\\d+\\s*ml.*")) {
                task.put("title", "足量饮水，每日约 " + targetMl + " ml");
            }
            String rationale = task.path("rationale").asText("").trim();
            if (!StringUtils.hasText(rationale)
                    || rationale.contains("落在")
                    || rationale.contains("上限")
                    || rationale.contains("×30")
                    || rationale.contains("x30")
                    || rationale.contains("估算")) {
                task.put("rationale", waterRationale(weightKg, targetMl));
            }
        }
    }

    public static String waterRationale(Double weightKg, int targetMl) {
        if (weightKg != null && weightKg >= 30 && weightKg <= 250) {
            int kg = (int) Math.round(weightKg);
            return "根据您的体重约 "
                    + kg
                    + " kg 计算，建议每天喝 "
                    + targetMl
                    + " 毫升水。少量多次，不要等渴了再喝。";
        }
        return "建议每天喝 " + targetMl + " 毫升水。少量多次，不要等渴了再喝。";
    }

    private static ObjectNode buildMedReminder(String titleSuffix, JsonNode contextSnapshot) {
        MealMedHint hint = mealMedicationHint(contextSnapshot);
        String drug = hint != null && StringUtils.hasText(hint.drugName()) ? hint.drugName() : null;
        String title;
        if (StringUtils.hasText(drug)) {
            title = "服药提醒：" + drug + "与第一口饭同服";
        } else if (StringUtils.hasText(titleSuffix)) {
            String cleaned = titleSuffix
                    .replaceFirst("^服药提醒[：:：]?", "")
                    .replaceFirst("^服药[：:：]?", "")
                    .trim();
            title = "服药提醒：" + (StringUtils.hasText(cleaned) ? cleaned : "随餐药物与第一口饭同服");
        } else {
            title = "服药提醒：随餐药物与第一口饭同服";
        }
        title = title.replaceAll("^[，,；;\\s]+", "").trim();
        ObjectNode t = JsonUtils.emptyObject();
        t.put("code", MED_REMINDER_CODE);
        t.put("title", title);
        t.put("category", MED_CATEGORY);
        t.put("frequency", hint != null && hint.tid() ? "TID" : "QD");
        t.put("timeSlot", "ALL");
        t.put(
                "rationale",
                "与饮食记录分开：具体服药请在「用药」页按时段打卡；本条为方案级提醒，便于核对药-餐配合");
        t.put("enabled", true);
        return t;
    }

    private static MealMedHint mealMedicationHint(JsonNode contextSnapshot) {
        if (contextSnapshot == null || contextSnapshot.isNull()) {
            return null;
        }
        JsonNode details = contextSnapshot.get("activeMedicationDetails");
        if (details != null && details.isArray()) {
            for (JsonNode d : details) {
                if (d == null || !d.isObject()) {
                    continue;
                }
                String name = d.path("name").asText("").trim();
                String timing = d.path("timingNote").asText("").trim();
                String freq = d.path("frequency").asText("").trim();
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                if (isMealTiming(timing) || isMealTiming(freq)) {
                    boolean tid = looksTid(freq) || isMealTiming(timing);
                    return new MealMedHint(name, tid);
                }
            }
        }
        JsonNode brief = contextSnapshot.path("intakeBrief").path("keyAnswers");
        if (brief.isObject()) {
            String mealRel = brief.path("medication.mealRelation").path("display").asText("");
            if (!StringUtils.hasText(mealRel)) {
                mealRel = brief.path("medication.mealRelation").asText("");
            }
            if (isMealTiming(mealRel)) {
                return new MealMedHint(null, true);
            }
        }
        return null;
    }

    private static boolean isMealTiming(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String t = text.toUpperCase(Locale.ROOT);
        return text.contains("第一口饭")
                || text.contains("随餐")
                || text.contains("跟餐")
                || text.contains("与餐")
                || text.contains("饭时")
                || t.contains("WITH_MEAL")
                || t.contains("WITHMEAL");
    }

    private static boolean looksTid(String frequency) {
        if (!StringUtils.hasText(frequency)) {
            return false;
        }
        String f = frequency.toUpperCase(Locale.ROOT).replace(" ", "");
        return f.contains("TID") || f.contains("一日三次") || f.contains("每日3") || f.contains("每日三");
    }

    private static boolean isDietLogTask(ObjectNode task) {
        String code = task.path("code").asText("");
        String category = task.path("category").asText("");
        String title = task.path("title").asText("");
        return "DIET_LOG".equalsIgnoreCase(code)
                || ("DIET".equalsIgnoreCase(category) && title.contains("记录") && title.contains("餐"));
    }

    private static boolean isMedReminder(ObjectNode task) {
        return MED_REMINDER_CODE.equalsIgnoreCase(task.path("code").asText(""))
                || MED_CATEGORY.equalsIgnoreCase(task.path("category").asText(""));
    }

    private static boolean hasMedReminder(ArrayNode tasks) {
        for (JsonNode n : tasks) {
            if (n instanceof ObjectNode obj && isMedReminder(obj)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSedentaryBreak(ObjectNode task) {
        String ref = task.path("relatedRef").asText("");
        String code = task.path("code").asText("");
        String title = task.path("title").asText("");
        return "SEDENTARY_BREAK".equalsIgnoreCase(ref)
                || "STANDING_BREAK".equalsIgnoreCase(ref)
                || "SIT_BREAK".equalsIgnoreCase(ref)
                || code.toUpperCase(Locale.ROOT).contains("SEDENTARY")
                || title.contains("久坐")
                || title.contains("每小时起身")
                || title.contains("每小时站");
    }

    private static boolean isWaterTask(ObjectNode task) {
        return WaterIntakeRules.looksLikeWaterTask(
                task.path("category").asText(""),
                task.path("title").asText(""),
                task.path("code").asText(""));
    }

    private static boolean containsMedicationCue(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        return text.contains("服药")
                || text.contains("吃药")
                || text.contains("用药")
                || text.contains("第一口饭")
                || text.contains("阿卡波糖");
    }

    private static String extractMedicationPart(String title) {
        String t = title.trim();
        for (String sep : List.of("，", ",", "；", ";", "。", "、")) {
            int idx = t.indexOf(sep);
            if (idx > 0) {
                String left = t.substring(0, idx).trim();
                String right = t.substring(idx + sep.length()).trim();
                if (containsMedicationCue(right) && !containsMedicationCue(left)) {
                    return right;
                }
                if (containsMedicationCue(left) && !containsMedicationCue(right)) {
                    return left;
                }
            }
        }
        return t.replace("记录三餐与加餐", "").replaceAll("^[，,；;\\s]+", "").trim();
    }

    private static int currentTargetMl(ObjectNode task) {
        if (task.has("targetMl") && task.get("targetMl").canConvertToInt()) {
            int ml = task.get("targetMl").asInt();
            if (ml > 0) {
                return ml;
            }
        }
        JsonNode payload = task.get("payload");
        if (payload != null && payload.has("targetMl") && payload.get("targetMl").canConvertToInt()) {
            int ml = payload.get("targetMl").asInt();
            if (ml > 0) {
                return ml;
            }
        }
        return 0;
    }

    private static Double weightKg(JsonNode contextSnapshot) {
        if (contextSnapshot == null || contextSnapshot.isNull()) {
            return null;
        }
        JsonNode metrics = contextSnapshot.get("latestMetrics");
        if (metrics != null && metrics.isArray()) {
            for (JsonNode m : metrics) {
                if (m == null || !m.isObject()) {
                    continue;
                }
                String type = m.path("metricType").asText(m.path("type").asText(""));
                if (!"WEIGHT".equalsIgnoreCase(type)) {
                    continue;
                }
                String value = m.path("value").asText("");
                if (!StringUtils.hasText(value) && m.path("value").isNumber()) {
                    return m.path("value").asDouble();
                }
                if (StringUtils.hasText(value)) {
                    try {
                        return Double.parseDouble(value.trim());
                    } catch (NumberFormatException ignored) {
                        return null;
                    }
                }
            }
        }
        JsonNode vitals = contextSnapshot.get("latestVitals");
        if (vitals != null && vitals.isObject()) {
            JsonNode w = vitals.get("weightKg");
            if (w != null && w.isNumber()) {
                return w.asDouble();
            }
        }
        JsonNode profile = contextSnapshot.get("profile");
        if (profile != null && profile.isObject()) {
            JsonNode w = profile.get("weightKg");
            if (w != null && w.isNumber()) {
                return w.asDouble();
            }
        }
        return null;
    }

    private record MealMedHint(String drugName, boolean tid) {
        String titleSuffix() {
            if (StringUtils.hasText(drugName)) {
                return drugName + "与第一口饭同服";
            }
            return "随餐药物与第一口饭同服";
        }
    }
}
