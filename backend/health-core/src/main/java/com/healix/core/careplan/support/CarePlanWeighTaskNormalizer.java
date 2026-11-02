package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

/**
 * 称重任务频次与标题对齐：goals.weighFrequency=WEEKLY 或进食障碍/身材焦虑高亮时，强制每周 1 次。
 * 避免模型写出「每周固定一天…」却把 frequency 标成 TIW。
 */
public final class CarePlanWeighTaskNormalizer {

    private CarePlanWeighTaskNormalizer() {}

    public static boolean preferWeeklyWeigh(JsonNode contextSnapshot) {
        if (contextSnapshot == null || contextSnapshot.isNull()) {
            return false;
        }
        JsonNode goals = contextSnapshot.get("goals");
        if (goals != null && "WEEKLY".equalsIgnoreCase(goals.path("weighFrequency").asText(""))) {
            return true;
        }
        JsonNode highlights = contextSnapshot.get("highlights");
        if (highlights != null && highlights.isArray()) {
            for (JsonNode h : highlights) {
                String field = h.path("field").asText("");
                if (field.contains("eatingDisorder") || field.contains("bodyAnxiety")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 原地修正 execution.tasks 里的称重任务。 */
    public static void normalize(JsonNode execution, JsonNode contextSnapshot) {
        if (!(execution instanceof ObjectNode) || !preferWeeklyWeigh(contextSnapshot)) {
            return;
        }
        JsonNode tasks = execution.get("tasks");
        if (!(tasks instanceof ArrayNode arr) || arr.isEmpty()) {
            return;
        }
        for (JsonNode n : arr) {
            if (!(n instanceof ObjectNode task) || !isWeighTask(task)) {
                continue;
            }
            task.put("frequency", "QW");
            String title = task.path("title").asText("").trim();
            if (!StringUtils.hasText(title)
                    || title.contains("每日")
                    || title.contains("每天")
                    || title.contains("一天三次")
                    || title.contains("每周3")
                    || title.contains("每周三次")) {
                task.put("title", "每周固定一天晨起空腹称体重");
            }
            String rationale = task.path("rationale").asText("").trim();
            if (!StringUtils.hasText(rationale) || rationale.contains("每日") || rationale.contains("每天")) {
                task.put("rationale", "称重频次控制在每周1次，关注趋势而非单日数字");
            }
        }
    }

    private static boolean isWeighTask(JsonNode task) {
        String code = task.path("code").asText("");
        String ref = task.path("relatedRef").asText("");
        String title = task.path("title").asText("");
        return "WEIGHT_LOG".equalsIgnoreCase(code)
                || "WEIGHT".equalsIgnoreCase(ref)
                || title.contains("称体重")
                || title.contains("称重")
                || (title.contains("体重") && (title.contains("记录") || title.contains("称")));
    }
}
