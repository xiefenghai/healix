package com.healix.core.worktask.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.followup.catalog.FollowupType;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import org.springframework.util.StringUtils;

/**
 * 工作台任务展示文案：类型默认名 + 少数 payload 细分（如入组首诊、方案复评）。
 */
public final class WorkspaceTaskLabels {

    public static final String PLAN_RENEW_LABEL = "方案待复评";

    private WorkspaceTaskLabels() {}

    public static String of(WorkspaceTaskType type, String payloadJson) {
        if (type == null) {
            return "";
        }
        if (type == WorkspaceTaskType.FOLLOW_UP) {
            String followupType = followupTypeFromPayload(payloadJson);
            if (FollowupType.CODES.contains(followupType)) {
                return FollowupType.require(followupType).label();
            }
        }
        if (type == WorkspaceTaskType.PLAN_CREATE
                && StringUtils.hasText(payloadJson)
                && payloadJson.contains("\"renew\":true")) {
            return PLAN_RENEW_LABEL;
        }
        return type.label();
    }

    /** 是否入组/首诊随访任务（payload.followupType = ONBOARDING）。 */
    public static boolean isOnboardingFollowUp(WorkspaceTaskType type, String payloadJson) {
        return type == WorkspaceTaskType.FOLLOW_UP
                && FollowupType.ONBOARDING.name().equals(followupTypeFromPayload(payloadJson));
    }

    private static String followupTypeFromPayload(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return "";
        }
        JsonNode root = JsonUtils.readTree(payloadJson);
        if (root == null || root.get("followupType") == null || root.get("followupType").isNull()) {
            return "";
        }
        return root.get("followupType").asText("").trim();
    }
}
