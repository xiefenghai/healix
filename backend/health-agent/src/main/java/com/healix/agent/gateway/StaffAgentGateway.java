package com.healix.agent.gateway;

import com.healix.agent.extract.ChatExtractCard;
import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.memory.AgentConversationService;
import com.healix.agent.memory.StaffSessionStore;
import com.healix.agent.react.StaffReActRunner;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.agent.support.AgentSessionIds;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.people.domain.PeopleProfile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * B 端员工智能体网关：会话/鉴权/落库；业务一律经 {@link StaffReActRunner} + Tools。
 */
@Service
@RequiredArgsConstructor
public class StaffAgentGateway {

    private final StaffSessionStore sessionStore;
    private final AgentConversationService conversationService;
    private final ArchiveAccessService archiveAccessService;
    private final AgentInteractionLogMapper interactionLogMapper;
    private final AiUsageGuard aiUsageGuard;
    private final StaffReActRunner staffReActRunner;

    public List<CapabilityView> capabilities() {
        List<CapabilityView> views = new ArrayList<>();
        for (AgentCapability cap : AgentCapability.values()) {
            if (cap.enabled()) {
                views.add(new CapabilityView(cap.name(), cap.label()));
            }
        }
        return views;
    }

    @Transactional
    public AgentResponse chat(AgentChatCommand cmd) {
        return doChat(cmd, null);
    }

    public void streamChat(AgentChatCommand cmd, Consumer<AgentStreamEvent> sink) {
        try {
            doChat(cmd, sink);
        } catch (Exception e) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.error(e.getMessage()));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.done());
        }
    }

    private AgentResponse doChat(AgentChatCommand cmd, Consumer<AgentStreamEvent> sink) {
        long startedAt = System.currentTimeMillis();
        if (isChatExtract(cmd)) {
            String problem = chatExtractProblem(cmd);
            if (problem != null) {
                return finishRestore(sink, startedAt, problem);
            }
        }
        boolean orgSession = !StringUtils.hasText(cmd.peopleId());
        if (!orgSession) {
            archiveAccessService.assertStaffCanAccessPeople(cmd.tenantId(), cmd.orgId(), cmd.peopleId());
        }
        aiUsageGuard.check(cmd.tenantId(), FeatureFlagKeyEnum.AI_STAFF_COPILOT, QuotaKeyEnum.AI_CALL_MONTHLY);

        String displayName;
        if (orgSession) {
            displayName = "机构今日";
        } else {
            PeopleProfile profile = archiveAccessService.requirePeopleInTenant(cmd.tenantId(), cmd.peopleId());
            displayName = profile.getDisplayName() == null ? "患者" : profile.getDisplayName();
        }

        String sessionId = conversationService.ensureSession(
                cmd.tenantId(),
                cmd.orgId(),
                cmd.staffId(),
                cmd.peopleId(),
                AgentSessionIds.normalize(cmd.sessionId()));
        var history = sessionStore.loadTurns(sessionId);

        AgentCapability forced = AgentCapability.fromHint(cmd.capabilityHint());
        if (orgSession
                && forced != null
                && forced != AgentCapability.GENERAL_CHAT
                && forced != AgentCapability.CREATE_ARCHIVE) {
            forced = AgentCapability.GENERAL_CHAT;
        }

        AgentResponse response = staffReActRunner.run(cmd, sessionId, history, displayName, sink, forced);
        if (orgSession
                && response != null
                && !AgentCapability.GENERAL_CHAT.name().equals(response.capability())
                && !AgentCapability.CREATE_ARCHIVE.name().equals(response.capability())) {
            response = new AgentResponse(
                    sessionId,
                    AgentCapability.GENERAL_CHAT.name(),
                    "GENERAL_CHAT",
                    "机构会话里请先建档或选择患者，再做方案/整理/识单。",
                    List.of(),
                    List.of(),
                    null);
        }

        if (StringUtils.hasText(response.reply())) {
            if (response.extracted() instanceof ChatExtractCard card) {
                String preview = response.reply();
                String storedUser = StringUtils.hasText(card.getSourceText()) ? card.getSourceText() : cmd.message();
                sessionStore.appendTurn(sessionId, preview, preview);
                conversationService.appendChatExtract(sessionId, storedUser, preview, card);
                Map<String, Object> payload =
                        JsonUtils.mapper().convertValue(card, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                response = new AgentResponse(
                        sessionId,
                        response.capability(),
                        response.intent(),
                        preview,
                        List.of(new AgentAction("chat_extract", card.getTitle(), null, card.getPeopleId(), payload)),
                        response.safetyFlags(),
                        card);
            } else {
                sessionStore.appendTurn(sessionId, cmd.message(), response.reply());
                conversationService.appendUser(sessionId, cmd.message());
                conversationService.appendAssistant(sessionId, response.reply(), response.actions());
            }
        }
        persistLog(cmd, sessionId, response);
        if (sink != null) {
            boolean keepExtracted =
                    AgentCapability.OCR_LAB.name().equals(response.capability())
                            || AgentCapability.OCR_EXAM.name().equals(response.capability())
                            || AgentCapability.OCR_MED.name().equals(response.capability())
                            || AgentCapability.REPORT_SUMMARY.name().equals(response.capability())
                            || AgentCapability.CARE_PLAN.name().equals(response.capability())
                            || AgentCapability.CHAT_EXTRACT.name().equals(response.capability());
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.result(
                            new AgentResponse(
                                    sessionId,
                                    response.capability(),
                                    response.intent(),
                                    response.reply(),
                                    response.actions(),
                                    response.safetyFlags(),
                                    keepExtracted ? response.extracted() : null)));
        }
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.done(System.currentTimeMillis() - startedAt));
        return new AgentResponse(
                sessionId,
                response.capability(),
                response.intent(),
                response.reply(),
                response.actions(),
                response.safetyFlags(),
                response.extracted());
    }

    private void persistLog(AgentChatCommand cmd, String sessionId, AgentResponse response) {
        AgentInteractionLog logEntity = new AgentInteractionLog();
        logEntity.setSessionId(sessionId);
        logEntity.setAgentType(AgentTypeEnum.CARE_COPILOT.name());
        logEntity.setTenantId(cmd.tenantId());
        logEntity.setPeopleId(cmd.peopleId());
        logEntity.setStaffId(cmd.staffId());
        logEntity.setIntent(response.intent());
        logEntity.setUserMessage(redactedUserMessage(cmd, response));
        logEntity.setPromptSnapshot("capability=" + response.capability());
        logEntity.setToolCallsJson(summarizeExtracted(response.extracted()));
        logEntity.setDraftReply(response.reply());
        logEntity.setFinalReply(response.reply());
        EntityMeta.onCreate(logEntity);
        interactionLogMapper.insert(logEntity);
    }

    private static String summarizeExtracted(Object extracted) {
        if (extracted == null) {
            return null;
        }
        if (extracted instanceof CarePlanBundleDto bundle) {
            var summary = JsonUtils.emptyObject();
            if (bundle.getPlan() != null) {
                summary.put("planId", bundle.getPlan().getId());
                summary.put("planStatus", bundle.getPlan().getStatus());
            }
            if (bundle.getDraft() != null) {
                summary.put("draftId", bundle.getDraft().getId());
                summary.put("draftSource", bundle.getDraft().getSource());
                summary.put("draftVersion", bundle.getDraft().getVersion());
            }
            return JsonUtils.toJson(summary);
        }
        if (extracted instanceof ChatExtractCard card) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("rows", card.getItems() == null ? 0 : card.getItems().size());
            summary.put("ignored", card.getIgnored() == null ? 0 : card.getIgnored().size());
            return JsonUtils.toJson(summary);
        }
        return JsonUtils.toJson(extracted);
    }

    private static boolean isChatExtract(AgentChatCommand cmd) {
        return cmd.capabilityHint() != null && "CHAT_EXTRACT".equalsIgnoreCase(cmd.capabilityHint().trim());
    }

    private static String chatExtractProblem(AgentChatCommand cmd) {
        if (!StringUtils.hasText(cmd.peopleId())) {
            return "请先选择患者";
        }
        if (cmd.message() == null || cmd.message().isBlank()) {
            return "请先粘贴一段记录";
        }
        if (cmd.message().trim().length() > 4000) {
            return "请拆短后再贴";
        }
        return null;
    }

    private AgentResponse finishRestore(Consumer<AgentStreamEvent> sink, long startedAt, String reply) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        extracted.put("restoreInput", true);
        AgentResponse response = new AgentResponse(
                null, AgentCapability.CHAT_EXTRACT.name(), "CHAT_EXTRACT", reply, List.of(), List.of(), extracted);
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.result(response));
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.done(System.currentTimeMillis() - startedAt));
        return response;
    }

    private static String redactedUserMessage(AgentChatCommand cmd, AgentResponse response) {
        if (AgentCapability.CHAT_EXTRACT.name().equals(response.capability())) {
            int chars = cmd.message() == null ? 0 : cmd.message().trim().length();
            if (response.extracted() instanceof ChatExtractCard card && StringUtils.hasText(card.getSourceText())) {
                chars = card.getSourceText().trim().length();
            }
            return "已整理一段记录，共 " + chars + " 字";
        }
        return cmd.message();
    }

    public record CapabilityView(String code, String label) {}
}
