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
 *   <li>{@code CALL_API}：API 键（NUDGE / CREATE_FOLLOWUP），点击后调现有 B API 并回执
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
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followupType", StringUtils.hasText(followupType) ? followupType : "PERIODIC");
        payload.put("createTask", true);
        payload.put("completeNow", false);
        return callApi(label, "CREATE_FOLLOWUP", peopleId, payload);
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
}
