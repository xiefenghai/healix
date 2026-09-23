package com.healix.agent.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * B 端灵犀回复附带的可点击动作。
 *
 * <p>{@code path} 含义随 {@code type} 变化：
 * <ul>
 *   <li>{@code OPEN_SHEET}：抽屉 mode（archive / followups / care-plan / …）
 *   <li>{@code NAVIGATE}：路由 path
 *   <li>{@code CALL_API}：API 键（NUDGE / CREATE_FOLLOWUP / PUBLISH_REPORT / PUBLISH_CARE_PLAN /
 *       SEND_CARE_CHAT / CLAIM_TASK），点击后调现有 B API 并回执
 *   <li>{@code TRIGGER_CAPABILITY}：会话内能力码（CARE_PLAN / REPORT_SUMMARY），点击后自动发带 hint 的消息
 * </ul>
 */
public record AgentAction(
        String type, String label, String path, String peopleId, Map<String, Object> payload) {

    public static AgentAction navigate(String label, String path) {
        return new AgentAction("NAVIGATE", label, path, null, null);
    }

    public static AgentAction focusPatient(String label, String peopleId) {
        return new AgentAction("FOCUS_PATIENT", label, null, peopleId, null);
    }

    public static AgentAction refresh(String label) {
        return new AgentAction("REFRESH", label, null, null, null);
    }

    public static AgentAction openSheet(String label, String mode, String peopleId) {
        return openSheet(label, mode, peopleId, null);
    }

    public static AgentAction openSheet(
            String label, String mode, String peopleId, Map<String, Object> payload) {
        return new AgentAction("OPEN_SHEET", label, mode, peopleId, payload);
    }

    /** 气泡内直接调现有 B API（人点确认后执行，非自动写库）。 */
    public static AgentAction callApi(
            String label, String apiKey, String peopleId, Map<String, Object> payload) {
        return new AgentAction("CALL_API", label, apiKey, peopleId, payload);
    }

    public static AgentAction nudgePatient(String label, String peopleId) {
        return callApi(label, "NUDGE", peopleId, null);
    }

    public static AgentAction createFollowupTask(String label, String peopleId, String followupType) {
        return createFollowupTask(label, peopleId, followupType, null);
    }

    /** @param draftContent 可选：写入随访指导建议草稿（summary/content.guidance） */
    public static AgentAction createFollowupTask(
            String label, String peopleId, String followupType, String draftContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        // followupType = FollowupType（ROUTINE/…），不是 record_type 的 PERIODIC
        payload.put("followupType", StringUtils.hasText(followupType) ? followupType : "ROUTINE");
        payload.put("createTask", true);
        payload.put("completeNow", false);
        if (StringUtils.hasText(draftContent)) {
            payload.put("draftContent", draftContent.trim());
        }
        return callApi(label, "CREATE_FOLLOWUP", peopleId, payload);
    }

    /** 确认发布管理报告（需已有 DRAFT + 点评文案）。 */
    public static AgentAction publishReport(
            String label,
            String peopleId,
            String reportId,
            String staffComment,
            String nextFocus,
            String quarterAdvice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", reportId);
        if (StringUtils.hasText(staffComment)) {
            payload.put("staffComment", staffComment.trim());
        }
        if (StringUtils.hasText(nextFocus)) {
            payload.put("nextFocus", nextFocus.trim());
        }
        if (StringUtils.hasText(quarterAdvice)) {
            payload.put("quarterAdvice", quarterAdvice.trim());
        }
        return callApi(label, "PUBLISH_REPORT", peopleId, payload);
    }

    /** 确认发布管理方案草稿。 */
    public static AgentAction publishCarePlan(String label, String peopleId) {
        return callApi(label, "PUBLISH_CARE_PLAN", peopleId, null);
    }

    /** 将沟通草稿直接发到 CareChat（需患者已绑 C）。 */
    public static AgentAction sendCareChat(String label, String peopleId, String draftContent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(draftContent)) {
            payload.put("draftContent", draftContent.trim());
        }
        return callApi(label, "SEND_CARE_CHAT", peopleId, payload);
    }

    /** 领取工作台 OPEN 任务。 */
    public static AgentAction claimTask(String label, String peopleId, String taskId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        return callApi(label, "CLAIM_TASK", peopleId, payload);
    }

    public static Map<String, Object> draftPayload(String draftContent, boolean openCreate) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (draftContent != null && !draftContent.isBlank()) {
            map.put("draftContent", draftContent.trim());
        }
        if (openCreate) {
            map.put("openCreate", true);
        }
        return map.isEmpty() ? null : map;
    }

    /** 打开报告审阅抽屉，并带上 AI 点评预填（不落库，人确认后发布）。 */
    public static AgentAction openReportReview(
            String label,
            String peopleId,
            String reportId,
            String staffComment,
            String nextFocus,
            String quarterAdvice) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reportId", reportId);
        payload.put("openReview", true);
        if (StringUtils.hasText(staffComment)) {
            payload.put("staffComment", staffComment.trim());
        }
        if (StringUtils.hasText(nextFocus)) {
            payload.put("nextFocus", nextFocus.trim());
        }
        if (StringUtils.hasText(quarterAdvice)) {
            payload.put("quarterAdvice", quarterAdvice.trim());
        }
        return openSheet(label, "reports", peopleId, payload);
    }

    /** 驾驶舱左侧切换 Tab（urgent / watch / mine）。 */
    public static AgentAction setCockpitTab(String label, String tab) {
        return new AgentAction("SET_COCKPIT_TAB", label, tab, null, null);
    }

    /**
     * 触发会话内能力（如 CARE_PLAN / REPORT_SUMMARY），前端点按后自动发一条带 capabilityHint 的消息。
     */
    public static AgentAction triggerCapability(String label, String capability, String peopleId) {
        return new AgentAction("TRIGGER_CAPABILITY", label, capability, peopleId, null);
    }
}
