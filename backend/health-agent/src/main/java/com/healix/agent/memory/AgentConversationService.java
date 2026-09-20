package com.healix.agent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.agent.gateway.AgentAction;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.agent.enums.AgentTypeEnum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 驾驶舱可见对话持久化：落 agent_session / agent_message；Redis 仍作 LLM 短窗上下文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentConversationService {

    private static final int MAX_MESSAGES = 200;
    private static final int MAX_SESSIONS = 50;
    private static final String AGENT_TYPE = AgentTypeEnum.CARE_COPILOT.name();
    private static final String TITLE_ORG = "机构简报会话";
    private static final String TITLE_PATIENT = "患者会话";

    private final AgentSessionMapper sessionMapper;
    private final AgentMessageMapper messageMapper;

    public record VisibleMessage(
            String id, String role, String content, List<AgentAction> actions, String createdAt) {}

    public record SessionBundle(String sessionId, String status, List<VisibleMessage> messages) {}

    public record SessionSummary(
            String sessionId,
            String title,
            String peopleId,
            String status,
            String preview,
            int messageCount,
            String gmtCreated,
            String gmtModified) {}

    public SessionBundle loadOrCreate(
            String tenantId, String orgId, String staffId, String peopleId, String preferredSessionId) {
        AgentSessionEntity session = null;
        if (StringUtils.hasText(preferredSessionId)) {
            session = requireOwned(preferredSessionId.trim(), staffId);
        }
        if (session == null) {
            session = sessionMapper.findActive(tenantId, orgId, staffId, blankToNull(peopleId), AGENT_TYPE);
        }
        if (session == null) {
            session = createSession(tenantId, orgId, staffId, peopleId);
        }
        return toBundle(session);
    }

    public SessionBundle getOwnedSession(String sessionId, String staffId) {
        AgentSessionEntity session = requireOwned(sessionId, staffId);
        if (session == null) {
            throw new BusinessException("会话不存在或无权查看");
        }
        return toBundle(session);
    }

    public List<SessionSummary> listSessions(
            String tenantId, String orgId, String staffId, String peopleId, int limit) {
        int lim = Math.min(Math.max(limit, 1), MAX_SESSIONS);
        List<AgentSessionEntity> rows = sessionMapper.listByStaff(
                tenantId, orgId, staffId, blankToNull(peopleId), AGENT_TYPE, lim);
        List<SessionSummary> out = new ArrayList<>();
        for (AgentSessionEntity row : rows) {
            int count = messageMapper.countBySession(row.getId());
            String preview = cleanPreview(messageMapper.lastPreview(row.getId()));
            out.add(new SessionSummary(
                    row.getId(),
                    StringUtils.hasText(row.getTitle()) ? row.getTitle() : defaultTitle(row.getPeopleId()),
                    row.getPeopleId(),
                    row.getStatus(),
                    preview,
                    count,
                    fmt(row.getGmtCreated()),
                    fmt(row.getGmtModified())));
        }
        return out;
    }

    /** 归档当前 ACTIVE，新建空会话。 */
    public SessionBundle startNew(
            String tenantId, String orgId, String staffId, String peopleId) {
        sessionMapper.closeActive(tenantId, orgId, staffId, blankToNull(peopleId), AGENT_TYPE);
        AgentSessionEntity session = createSession(tenantId, orgId, staffId, peopleId);
        return toBundle(session);
    }

    /** 将历史会话重新设为 ACTIVE（同范围其它 ACTIVE 先关闭）。 */
    public SessionBundle resume(
            String tenantId, String orgId, String staffId, String sessionId) {
        AgentSessionEntity session = requireOwned(sessionId, staffId);
        if (session == null) {
            throw new BusinessException("会话不存在或无权查看");
        }
        sessionMapper.closeActive(
                tenantId, orgId, staffId, blankToNull(session.getPeopleId()), AGENT_TYPE);
        sessionMapper.reactivate(session.getId());
        session = sessionMapper.findById(session.getId());
        return toBundle(session);
    }

    public String ensureSession(
            String tenantId, String orgId, String staffId, String peopleId, String sessionId) {
        if (StringUtils.hasText(sessionId)) {
            AgentSessionEntity existing = requireOwned(sessionId.trim(), staffId);
            if (existing != null) {
                if (!"ACTIVE".equalsIgnoreCase(existing.getStatus())) {
                    sessionMapper.closeActive(
                            tenantId, orgId, staffId, blankToNull(existing.getPeopleId()), AGENT_TYPE);
                    sessionMapper.reactivate(existing.getId());
                } else {
                    sessionMapper.touch(existing.getId());
                }
                return existing.getId();
            }
        }
        AgentSessionEntity active =
                sessionMapper.findActive(tenantId, orgId, staffId, blankToNull(peopleId), AGENT_TYPE);
        if (active != null) {
            sessionMapper.touch(active.getId());
            return active.getId();
        }
        return createSession(tenantId, orgId, staffId, peopleId).getId();
    }

    public void appendUser(String sessionId, String text) {
        insert(sessionId, "USER", text, null, null);
        maybeUpdateTitle(sessionId, text);
    }

    public void appendAssistant(String sessionId, String text, List<AgentAction> actions) {
        insert(sessionId, "ASSISTANT", text, null, actions);
    }

    public void appendSystem(String sessionId, String text, List<AgentAction> actions) {
        // 简报刷新只保留最新一条，避免浏览器刷新后堆出多条「今日建议」
        if (StringUtils.hasText(sessionId)) {
            messageMapper.softDeleteBySessionAndTool(sessionId, "briefing");
        }
        insert(sessionId, "SYSTEM", text, "briefing", actions);
    }

    public void appendReceipt(String sessionId, String text) {
        insert(sessionId, "SYSTEM", text, "receipt", null);
    }

    private void maybeUpdateTitle(String sessionId, String userText) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(userText)) {
            return;
        }
        AgentSessionEntity session = sessionMapper.findById(sessionId);
        if (session == null) {
            return;
        }
        String title = session.getTitle();
        if (StringUtils.hasText(title)
                && !TITLE_ORG.equals(title)
                && !TITLE_PATIENT.equals(title)
                && !title.startsWith("会话 ")) {
            return;
        }
        String next = userText.trim().replace('\n', ' ');
        if (next.length() > 24) {
            next = next.substring(0, 24) + "…";
        }
        sessionMapper.updateTitle(sessionId, next);
    }

    private void insert(
            String sessionId, String role, String text, String toolName, List<AgentAction> actions) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(text)) {
            return;
        }
        try {
            AgentMessageEntity row = new AgentMessageEntity();
            EntityMeta.onCreate(row);
            row.setSessionId(sessionId);
            row.setRole(role);
            row.setContent(pack(text, actions));
            row.setToolName(toolName);
            messageMapper.insert(row);
            sessionMapper.touch(sessionId);
        } catch (Exception e) {
            log.warn("persist agent message failed session={}: {}", sessionId, e.getMessage());
        }
    }

    private AgentSessionEntity createSession(
            String tenantId, String orgId, String staffId, String peopleId) {
        AgentSessionEntity row = new AgentSessionEntity();
        EntityMeta.onCreate(row);
        row.setAgentType(AGENT_TYPE);
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setStaffId(staffId);
        row.setPeopleId(blankToNull(peopleId));
        row.setTitle(defaultTitle(peopleId));
        row.setStatus("ACTIVE");
        sessionMapper.insert(row);
        return row;
    }

    private SessionBundle toBundle(AgentSessionEntity session) {
        List<AgentMessageEntity> rows = messageMapper.listBySession(session.getId(), MAX_MESSAGES);
        List<VisibleMessage> messages = new ArrayList<>();
        for (AgentMessageEntity row : rows) {
            messages.add(toVisible(row));
        }
        return new SessionBundle(session.getId(), session.getStatus(), messages);
    }

    private AgentSessionEntity requireOwned(String sessionId, String staffId) {
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        AgentSessionEntity session = sessionMapper.findById(sessionId.trim());
        if (session == null || !staffId.equals(session.getStaffId())) {
            return null;
        }
        return session;
    }

    private static String defaultTitle(String peopleId) {
        return StringUtils.hasText(peopleId) ? TITLE_PATIENT : TITLE_ORG;
    }

    private static String cleanPreview(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        Parsed parsed = unpack(raw);
        String text = parsed.text().replace('\n', ' ').trim();
        if (text.length() > 48) {
            return text.substring(0, 48) + "…";
        }
        return text;
    }

    private static String fmt(java.time.LocalDateTime t) {
        return t == null ? null : t.toString().replace('T', ' ');
    }

    private static VisibleMessage toVisible(AgentMessageEntity row) {
        Parsed parsed = unpack(row.getContent());
        String created = fmt(row.getGmtCreated());
        String roleRaw = row.getRole() == null ? "ASSISTANT" : row.getRole().toUpperCase();
        String role =
                "USER".equals(roleRaw) ? "user" : "SYSTEM".equals(roleRaw) ? "system" : "assistant";
        return new VisibleMessage(row.getId(), role, parsed.text(), parsed.actions(), created);
    }

    static String pack(String text, List<AgentAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return text;
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentAction a : actions) {
            if (a == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", a.type());
            m.put("label", a.label());
            if (a.path() != null) {
                m.put("path", a.path());
            }
            if (a.peopleId() != null) {
                m.put("peopleId", a.peopleId());
            }
            if (a.payload() != null) {
                m.put("payload", a.payload());
            }
            list.add(m);
        }
        if (list.isEmpty()) {
            return text;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("actions", list);
        return text + "\n<!--healix-meta:" + JsonUtils.toJson(meta) + "-->";
    }

    static Parsed unpack(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new Parsed("", List.of());
        }
        int marker = raw.lastIndexOf("<!--healix-meta:");
        int end = raw.lastIndexOf("-->");
        if (marker < 0 || end <= marker) {
            return new Parsed(raw, List.of());
        }
        String text = raw.substring(0, marker).stripTrailing();
        String json = raw.substring(marker + "<!--healix-meta:".length(), end).trim();
        List<AgentAction> actions = new ArrayList<>();
        try {
            Map<String, Object> meta =
                    JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {});
            Object arr = meta == null ? null : meta.get("actions");
            if (arr instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String type = str(map.get("type"));
                    String label = str(map.get("label"));
                    if (!StringUtils.hasText(type) || !StringUtils.hasText(label)) {
                        continue;
                    }
                    Map<String, Object> payload = null;
                    if (map.get("payload") instanceof Map<?, ?> p) {
                        payload = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> e : p.entrySet()) {
                            if (e.getKey() != null) {
                                payload.put(String.valueOf(e.getKey()), e.getValue());
                            }
                        }
                    }
                    actions.add(new AgentAction(
                            type, label, str(map.get("path")), str(map.get("peopleId")), payload));
                }
            }
        } catch (Exception ignored) {
            // keep text only
        }
        return new Parsed(text, actions);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private record Parsed(String text, List<AgentAction> actions) {}
}
