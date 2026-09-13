package com.healix.agent.gateway;

import com.healix.agent.careplan.CarePlanAgentService;
import com.healix.agent.careplan.CarePlanStreamSummary;
import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.memory.StaffSessionStore;
import com.healix.agent.support.AgentSessionIds;
import com.healix.agent.support.AiUsageGuard;
import com.healix.agent.skill.AgentSkill;
import com.healix.agent.skill.AgentSkillContext;
import com.healix.agent.skill.CarePlanSkill;
import com.healix.agent.skill.GeneralChatSkill;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.people.domain.PeopleProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * B 端员工智能体网关：支持有 peopleId 的患者会话，以及驾驶舱无 peopleId 的机构级会话。
 */
@Service
@RequiredArgsConstructor
public class StaffAgentGateway {

    private final List<AgentSkill> skills;
    private final StaffSessionStore sessionStore;
    private final ArchiveAccessService archiveAccessService;
    private final AgentInteractionLogMapper interactionLogMapper;
    private final CarePlanAgentService carePlanAgentService;
    private final GeneralChatSkill generalChatSkill;
    private final AiUsageGuard aiUsageGuard;

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

        String sessionId = AgentSessionIds.normalize(cmd.sessionId());
        var history = sessionStore.loadTurns(sessionId);
        AgentCapability routed = route(cmd);
        if (orgSession && routed != AgentCapability.GENERAL_CHAT) {
            // 机构首页会话禁止需患者的能力（方案 / OCR 等）
            routed = AgentCapability.GENERAL_CHAT;
        }
        AgentSkillContext ctx = new AgentSkillContext(cmd, sessionId, history, displayName);

        AgentResponse response;
        if (routed == AgentCapability.CARE_PLAN) {
            response = streamCarePlan(ctx, sink);
        } else if (sink != null) {
            response = generalChatSkill.executeStream(ctx, sink);
        } else {
            AgentSkill skill = resolveSkill(routed, cmd);
            response = skill.execute(ctx);
        }

        sessionStore.appendTurn(sessionId, cmd.message(), response.reply());
        persistLog(cmd, sessionId, response);
        if (sink != null) {
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.result(
                            new AgentResponse(
                                    response.sessionId(),
                                    response.capability(),
                                    response.intent(),
                                    response.reply(),
                                    response.actions(),
                                    response.safetyFlags(),
                                    null)));
        }
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.done());
        return response;
    }

    private AgentResponse streamCarePlan(AgentSkillContext ctx, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        String instruction = StringUtils.hasText(cmd.message()) ? cmd.message() : "生成管理方案";
        CarePlanBundleDto bundle;
        if (sink != null) {
            bundle = carePlanAgentService.generateStream(
                    cmd.tenantId(),
                    cmd.orgId(),
                    cmd.peopleId(),
                    cmd.staffId(),
                    instruction,
                    null,
                    null,
                    true,
                    sink,
                    true);
        } else {
            bundle = carePlanAgentService.generateStream(
                    cmd.tenantId(),
                    cmd.orgId(),
                    cmd.peopleId(),
                    cmd.staffId(),
                    instruction,
                    null,
                    null,
                    true,
                    null,
                    true);
        }
        String path = "/workspace/patients/" + cmd.peopleId() + "/care-plan";
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CARE_PLAN.name(),
                "CARE_PLAN_GENERATE",
                CarePlanStreamSummary.buildReply(bundle),
                List.of(AgentAction.navigate("查看方案草稿", path)),
                List.of(),
                bundle);
    }

    private AgentCapability route(AgentChatCommand cmd) {
        if (!StringUtils.hasText(cmd.peopleId())) {
            return AgentCapability.GENERAL_CHAT;
        }
        AgentCapability hinted = AgentCapability.fromHint(cmd.capabilityHint());
        if (hinted != null && hinted.enabled()) {
            return hinted;
        }
        if (CarePlanSkill.matchesMessage(cmd.message())) {
            return AgentCapability.CARE_PLAN;
        }
        return AgentCapability.GENERAL_CHAT;
    }

    private AgentSkill resolveSkill(AgentCapability routed, AgentChatCommand cmd) {
        for (AgentSkill skill : skills) {
            if (skill.supports(routed, cmd)) {
                return skill;
            }
        }
        return generalChatSkill;
    }

    private void persistLog(AgentChatCommand cmd, String sessionId, AgentResponse response) {
        AgentInteractionLog logEntity = new AgentInteractionLog();
        logEntity.setSessionId(sessionId);
        logEntity.setAgentType(AgentTypeEnum.CARE_COPILOT.name());
        logEntity.setTenantId(cmd.tenantId());
        logEntity.setPeopleId(cmd.peopleId());
        logEntity.setStaffId(cmd.staffId());
        logEntity.setIntent(response.intent());
        logEntity.setUserMessage(cmd.message());
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
        return JsonUtils.toJson(extracted);
    }

    public record CapabilityView(String code, String label) {}
}
