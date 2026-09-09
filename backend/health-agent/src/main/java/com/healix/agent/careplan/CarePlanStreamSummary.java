package com.healix.agent.careplan;

import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.dto.CarePlanDraftDto;
import org.springframework.util.StringUtils;

public final class CarePlanStreamSummary {

    private CarePlanStreamSummary() {}

    public static String buildReply(CarePlanBundleDto bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append("管理方案草稿已生成，请审阅后发布。\n");
        if (bundle == null || bundle.getDraft() == null) {
            return sb.toString();
        }
        CarePlanDraftDto draft = bundle.getDraft();
        if (StringUtils.hasText(draft.getSource())) {
            sb.append("\n生成方式：").append(sourceLabel(draft.getSource()));
        }
        if (draft.getExercise() != null && draft.getExercise().has("goal")) {
            sb.append("\n运动目标：").append(draft.getExercise().get("goal").asText());
        }
        if (draft.getDiet() != null && draft.getDiet().has("principles") && draft.getDiet().get("principles").isArray()) {
            var principles = draft.getDiet().get("principles");
            if (!principles.isEmpty()) {
                sb.append("\n饮食原则：").append(principles.get(0).asText());
                if (principles.size() > 1) {
                    sb.append(" 等").append(principles.size()).append("项");
                }
            }
        }
        if (draft.getExecution() != null && draft.getExecution().has("tasks") && draft.getExecution().get("tasks").isArray()) {
            sb.append("\n执行任务：").append(draft.getExecution().get("tasks").size()).append(" 条");
        }
        if (draft.getContextSnapshot() != null && draft.getContextSnapshot().has("summary")) {
            sb.append("\n\n方案总结：").append(draft.getContextSnapshot().get("summary").asText());
        }
        return sb.toString();
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
