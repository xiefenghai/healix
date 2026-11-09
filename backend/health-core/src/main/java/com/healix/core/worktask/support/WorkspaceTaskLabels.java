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
    /** 患者已初步交卷，健管师需核对/补全并确认后才算正式评估 */
    public static final String INTAKE_REVIEW_LABEL = "待确认评估";
    /** 历史文案，兼容旧任务 summary / 前端匹配 */
    public static final String INTAKE_REVIEW_LABEL_LEGACY = "待确认（患者已交卷）";
    public static final String INTAKE_REVIEW_LABEL_LEGACY2 = "待复核（新问卷）";

    private WorkspaceTaskLabels() {}

    /** 是否待确认评估类展示文案（含历史别名）。 */
    public static boolean isIntakeReviewLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return false;
        }
        String t = label.trim();
        return INTAKE_REVIEW_LABEL.equals(t)
                || INTAKE_REVIEW_LABEL_LEGACY.equals(t)
                || INTAKE_REVIEW_LABEL_LEGACY2.equals(t)
                || t.contains("待确认");
    }

    public static String of(WorkspaceTaskType type, String payloadJson) {
        if (type == null) {
            return "";
        }
        if (isPatientIntakeReview(payloadJson)) {
            return INTAKE_REVIEW_LABEL;
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

    public static boolean isPatientIntakeReview(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return false;
        }
        JsonNode root = JsonUtils.readTree(payloadJson);
        if (root == null || root.get("patientIntakeReview") == null || root.get("patientIntakeReview").isNull()) {
            return false;
        }
        return root.get("patientIntakeReview").asBoolean(false)
                || "true".equalsIgnoreCase(root.get("patientIntakeReview").asText(""));
    }

    /** 是否入组/首诊随访任务（payload.followupType = ONBOARDING）。 */
    public static boolean isOnboardingFollowUp(WorkspaceTaskType type, String payloadJson) {
        return type == WorkspaceTaskType.FOLLOW_UP
                && FollowupType.ONBOARDING.name().equals(followupTypeFromPayload(payloadJson));
    }

    public static String followupTypeFromPayload(String payloadJson) {
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
