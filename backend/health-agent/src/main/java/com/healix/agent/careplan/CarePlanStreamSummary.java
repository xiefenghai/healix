package com.healix.agent.careplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.dto.CarePlanDraftDto;
import org.springframework.util.StringUtils;

/** 方案结束后的可读摘要（流式正文优先由 CarePlanReadableStreamer 推送）。 */
public final class CarePlanStreamSummary {

    private CarePlanStreamSummary() {}

    public static String buildReply(CarePlanBundleDto bundle) {
        return buildReply(bundle, false);
    }

    public static String buildReply(CarePlanBundleDto bundle, boolean revised) {
        StringBuilder sb = new StringBuilder();
        sb.append(revised ? "管理方案草稿已按你的要求修订，请审阅后发布。" : "管理方案草稿已生成，请审阅后发布。");
        if (bundle == null || bundle.getDraft() == null) {
            return sb.toString();
        }
        CarePlanDraftDto draft = bundle.getDraft();
        if (StringUtils.hasText(draft.getSource())) {
            sb.append("\n生成方式：").append(sourceLabel(draft.getSource()));
        }

        appendExercise(sb, draft.getExercise());
        appendDiet(sb, draft.getDiet());
        appendExecution(sb, draft.getExecution());

        if (draft.getContextSnapshot() != null && draft.getContextSnapshot().has("summary")) {
            String summary = draft.getContextSnapshot().get("summary").asText();
            if (StringUtils.hasText(summary)) {
                sb.append("\n\n方案总结\n").append(summary);
            }
        }
        String goal = null;
        if (bundle.getPlan() != null) {
            goal = bundle.getPlan().getGoalSummary();
        }
        if (!StringUtils.hasText(goal) && draft.getExercise() != null && draft.getExercise().has("goal")) {
            goal = draft.getExercise().get("goal").asText();
        }
        if (StringUtils.hasText(goal)) {
            sb.append("\n\n阶段目标\n").append(goal);
        }
        return sb.toString();
    }

    private static void appendExercise(StringBuilder sb, JsonNode exercise) {
        if (exercise == null || exercise.isNull() || exercise.isMissingNode()) {
            return;
        }
        sb.append("\n\n运动方案");
        if (exercise.has("goal") && StringUtils.hasText(exercise.get("goal").asText())) {
            sb.append("\n目标：").append(exercise.get("goal").asText());
        }
        JsonNode weekly = exercise.get("weeklyPlan");
        if (weekly != null && weekly.isArray() && !weekly.isEmpty()) {
            sb.append("\n周计划：").append(weekly.size()).append(" 天安排");
        }
        appendStringArray(sb, "禁忌", exercise.get("contraindications"));
        appendStringArray(sb, "注意", exercise.get("precautions"));
        if (exercise.has("reviewHint") && StringUtils.hasText(exercise.get("reviewHint").asText())) {
            sb.append("\n复评：").append(exercise.get("reviewHint").asText());
        }
    }

    private static void appendDiet(StringBuilder sb, JsonNode diet) {
        if (diet == null || diet.isNull() || diet.isMissingNode()) {
            return;
        }
        sb.append("\n\n饮食方案");
        if (diet.has("calorieHint") && StringUtils.hasText(diet.get("calorieHint").asText())) {
            sb.append("\n热量建议：").append(diet.get("calorieHint").asText());
        }
        appendStringArray(sb, "原则", diet.get("principles"));
        appendLabelArray(sb, "推荐", diet.get("recommended"));
        appendLabelArray(sb, "限制", diet.get("limited"));
        appendLabelArray(sb, "过敏规避", diet.get("allergensAvoid"));
        JsonNode sample = diet.get("sampleDay");
        if (sample != null && sample.isObject()) {
            sb.append("\n示例日：");
            appendMeal(sb, "早餐", sample.get("breakfast"));
            appendMeal(sb, "午餐", sample.get("lunch"));
            appendMeal(sb, "晚餐", sample.get("dinner"));
        }
        if (diet.has("notes") && StringUtils.hasText(diet.get("notes").asText())) {
            sb.append("\n补充：").append(diet.get("notes").asText());
        }
    }

    private static void appendExecution(StringBuilder sb, JsonNode execution) {
        if (execution == null || execution.isNull() || execution.isMissingNode()) {
            return;
        }
        sb.append("\n\n执行计划");
        if (execution.has("horizonDays") && execution.get("horizonDays").canConvertToInt()) {
            sb.append("\n周期：").append(execution.get("horizonDays").asInt()).append(" 天");
        }
        JsonNode tasks = execution.get("tasks");
        if (tasks != null && tasks.isArray() && !tasks.isEmpty()) {
            sb.append("\n打卡任务：");
            int i = 1;
            for (JsonNode task : tasks) {
                String title = text(task, "title");
                if (StringUtils.hasText(title)) {
                    sb.append('\n').append(i++).append(". ").append(title);
                }
            }
        }
    }

    private static void appendMeal(StringBuilder sb, String label, JsonNode node) {
        if (node == null || !StringUtils.hasText(node.asText())) {
            return;
        }
        sb.append("\n· ").append(label).append("：").append(node.asText());
    }

    private static void appendStringArray(StringBuilder sb, String label, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return;
        }
        sb.append('\n').append(label).append("：");
        for (JsonNode item : arr) {
            if (item != null && StringUtils.hasText(item.asText())) {
                sb.append("\n· ").append(item.asText());
            }
        }
    }

    private static void appendLabelArray(StringBuilder sb, String label, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return;
        }
        sb.append('\n').append(label).append("：");
        for (JsonNode item : arr) {
            String text = labelOf(item);
            if (StringUtils.hasText(text)) {
                sb.append("\n· ").append(text);
            }
        }
    }

    private static String labelOf(JsonNode item) {
        if (item == null || item.isNull()) {
            return null;
        }
        if (item.isTextual()) {
            return item.asText();
        }
        String label = text(item, "label");
        if (StringUtils.hasText(label)) {
            return label;
        }
        return text(item, "code");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return null;
        }
        return node.get(field).asText(null);
    }

    private static String sourceLabel(String source) {
        return switch (source) {
            case "LLM" -> "AI 生成";
            case "TEMPLATE" -> "模板生成";
            case "TEMPLATE_FALLBACK" -> "AI 失败后模板降级";
            case "LLM_THEN_EDIT" -> source;
            default -> source;
        };
    }
}
