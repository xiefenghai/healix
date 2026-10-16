package com.healix.core.water.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlanTask;
import org.springframework.util.StringUtils;

public final class WaterTaskTargetResolver {

    private WaterTaskTargetResolver() {}

    public static Integer targetMlOf(CarePlanTask task) {
        if (task == null) {
            return null;
        }
        Integer fromPayload = positiveInt(task.getPayloadJson(), "targetMl");
        if (fromPayload != null) {
            return fromPayload;
        }
        return parseTargetFromTitle(task.getTitle());
    }

    public static Integer positiveInt(String payloadJson, String field) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            JsonNode n = JsonUtils.readTree(payloadJson);
            JsonNode v = n == null ? null : n.get(field);
            if (v == null || !v.canConvertToInt()) {
                return null;
            }
            int i = v.asInt();
            return i > 0 ? i : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 兼容旧标题「足量饮水，每日约 1600 ml」。 */
    public static Integer parseTargetFromTitle(String title) {
        if (!StringUtils.hasText(title)) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})\\s*m?l", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(title);
        if (m.find()) {
            int v = Integer.parseInt(m.group(1));
            if (v >= WaterIntakeRules.MIN_TARGET_ML && v <= WaterIntakeRules.MAX_TARGET_ML + 500) {
                return v;
            }
        }
        return null;
    }
}
