package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 打鼾 + 晨起不解乏 + 超重/肥胖或慢病（糖/压）时，强制写入可执行的睡眠呼吸监测提醒。
 * 不写成已确诊 OSA，只做就医筛查建议。
 */
public final class CarePlanOsaHintSupport {

    public static final String HINT =
            "睡眠呼吸：建议尽快前往呼吸内科或耳鼻喉科进行睡眠呼吸监测（多导睡眠图）。"
                    + "打鼾严重会明显影响血压和血糖控制，请勿忽视。";

    private CarePlanOsaHintSupport() {}

    public static boolean shouldHint(JsonNode contextSnapshot) {
        if (contextSnapshot == null || contextSnapshot.isNull()) {
            return false;
        }
        return hasSnoring(contextSnapshot)
                && hasMorningFatigue(contextSnapshot)
                && hasMetabolicOrWeightRisk(contextSnapshot);
    }

    /** 写入 diet.notes；必要时强化 exercise.precautions 里过弱的睡眠表述。 */
    public static void apply(ObjectNode diet, ObjectNode exercise, JsonNode contextSnapshot) {
        if (!shouldHint(contextSnapshot)) {
            return;
        }
        if (diet != null) {
            injectDietNote(diet);
        }
        if (exercise != null) {
            strengthenExercisePrecaution(exercise);
        }
    }

    static void injectDietNote(ObjectNode diet) {
        String raw = diet.path("notes").asText("");
        List<String> lines = splitNotes(raw);
        List<String> out = new ArrayList<>();
        boolean hasStrong = false;
        for (String line : lines) {
            if (isWeakSleepHint(line)) {
                continue;
            }
            if (isStrongSleepHint(line)) {
                hasStrong = true;
            }
            out.add(line);
        }
        if (!hasStrong) {
            out.add(HINT);
        }
        diet.put("notes", String.join("；", out));
    }

    static void strengthenExercisePrecaution(ObjectNode exercise) {
        JsonNode arr = exercise.get("precautions");
        List<String> kept = new ArrayList<>();
        boolean hasStrong = false;
        if (arr instanceof ArrayNode precautions) {
            for (JsonNode n : precautions) {
                String t = n == null ? "" : n.asText("").trim();
                if (!StringUtils.hasText(t) || isWeakSleepHint(t)) {
                    continue;
                }
                if (isStrongSleepHint(t)) {
                    hasStrong = true;
                }
                kept.add(t);
            }
        }
        if (!hasStrong) {
            kept.add(HINT);
        }
        ArrayNode next = exercise.putArray("precautions");
        for (String t : kept) {
            next.add(t);
        }
    }

    private static boolean hasSnoring(JsonNode snap) {
        JsonNode sleep = snap.path("basicArchive").path("sleep");
        String disorder = sleep.path("disorder").asText("");
        if ("SNORING".equalsIgnoreCase(disorder) || disorder.toUpperCase(Locale.ROOT).contains("SNOR")) {
            return true;
        }
        if (labelsContain(sleep.get("disorderLabels"), "打鼾", "呼噜", "睡眠呼吸")) {
            return true;
        }
        if (labelsContain(sleep.get("disorderLabel"), "打鼾", "呼噜", "睡眠呼吸")) {
            return true;
        }
        String blob = snap.toString();
        return blob.contains("打鼾") || blob.contains("打呼噜") || blob.contains("SNORING");
    }

    private static boolean hasMorningFatigue(JsonNode snap) {
        JsonNode sleep = snap.path("basicArchive").path("sleep");
        String refreshed = sleep.path("refreshed").asText("");
        if ("NO".equalsIgnoreCase(refreshed) || "PARTIAL".equalsIgnoreCase(refreshed)) {
            return true;
        }
        String label = sleep.path("refreshedLabel").asText("");
        if (label.contains("疲惫") || label.contains("疲劳") || label.contains("不解乏")) {
            return true;
        }
        String blob = snap.toString();
        return blob.contains("极度疲劳") || blob.contains("有些疲惫") || blob.contains("早上起来仍觉得疲惫");
    }

    private static boolean hasMetabolicOrWeightRisk(JsonNode snap) {
        Double bmi = bmiOf(snap);
        if (bmi != null && bmi >= 24.0) {
            return true;
        }
        Set<String> codes = new LinkedHashSet<>();
        addCodes(codes, snap.get("diseaseCodes"));
        addCodes(codes, snap.path("goals").path("careTracks"));
        addCodes(codes, snap.path("goals").path("prefs").path("careTracks"));
        for (String c : codes) {
            String u = c.toUpperCase(Locale.ROOT);
            if (u.contains("DIABETES")
                    || u.contains("HYPERTENSION")
                    || u.contains("OBESITY")
                    || u.contains("WEIGHT_LOSS")
                    || "HTN".equals(u)
                    || "DM".equals(u)
                    || "T1DM".equals(u)
                    || "T2DM".equals(u)) {
                return true;
            }
        }
        String blob = snap.toString();
        return blob.contains("肥胖")
                || blob.contains("超重")
                || blob.contains("糖尿病")
                || blob.contains("高血压")
                || blob.contains("BMI");
    }

    private static Double bmiOf(JsonNode snap) {
        JsonNode bmi = snap.path("latestMetrics");
        if (bmi.isArray()) {
            for (JsonNode m : bmi) {
                String type = m.path("metricType").asText(m.path("type").asText(""));
                if (!"BMI".equalsIgnoreCase(type)) {
                    continue;
                }
                if (m.path("value").isNumber()) {
                    return m.path("value").asDouble();
                }
                try {
                    return Double.parseDouble(m.path("value").asText("").trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        JsonNode profileBmi = snap.path("profile").path("bmi");
        if (profileBmi.isNumber()) {
            return profileBmi.asDouble();
        }
        return null;
    }

    private static void addCodes(Set<String> out, JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                    out.add(n.asText().trim());
                }
            }
            return;
        }
        if (node.isTextual() && StringUtils.hasText(node.asText())) {
            out.add(node.asText().trim());
        }
    }

    private static boolean labelsContain(JsonNode node, String... needles) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isArray()) {
            for (JsonNode n : node) {
                if (containsAny(n.asText(""), needles)) {
                    return true;
                }
            }
            return false;
        }
        return containsAny(node.asText(""), needles);
    }

    private static boolean containsAny(String text, String... needles) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        for (String n : needles) {
            if (StringUtils.hasText(n) && text.contains(n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWeakSleepHint(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        if (isStrongSleepHint(text)) {
            return false;
        }
        return text.contains("关注睡眠呼吸")
                || text.contains("留意睡眠呼吸")
                || text.contains("注意睡眠呼吸")
                || (text.contains("睡眠呼吸") && text.contains("情况") && !text.contains("监测"));
    }

    private static boolean isStrongSleepHint(String text) {
        return StringUtils.hasText(text)
                && text.contains("睡眠呼吸监测")
                && (text.contains("多导睡眠") || text.contains("呼吸内科") || text.contains("耳鼻喉"));
    }

    private static List<String> splitNotes(String raw) {
        List<String> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        for (String part : raw.split("[；;\\n]+")) {
            String p = part.trim();
            if (StringUtils.hasText(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
