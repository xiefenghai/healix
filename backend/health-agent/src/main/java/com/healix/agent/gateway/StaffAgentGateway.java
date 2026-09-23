package com.healix.agent.gateway;

import com.healix.agent.careplan.CarePlanAgentService;
import com.healix.agent.careplan.CarePlanStreamSummary;
import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.memory.AgentConversationService;
import com.healix.agent.memory.StaffSessionStore;
import com.healix.agent.ocr.ObservationReportIngestService;
import com.healix.agent.support.AgentSessionIds;
import com.healix.agent.support.AiUsageGuard;
import com.healix.agent.skill.AgentSkill;
import com.healix.agent.skill.AgentSkillContext;
import com.healix.agent.skill.CarePlanSkill;
import com.healix.agent.skill.GeneralChatSkill;
import com.healix.agent.skill.OcrExamSkill;
import com.healix.agent.skill.OcrLabSkill;
import com.healix.agent.skill.OcrMedSkill;
import com.healix.agent.skill.ReportSummarySkill;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.observation.dto.ObservationOcrPreviewDto;
import com.healix.core.people.domain.PeopleProfile;
import java.util.ArrayList;
import java.util.Base64;
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
    private final AgentConversationService conversationService;
    private final ArchiveAccessService archiveAccessService;
    private final AgentInteractionLogMapper interactionLogMapper;
    private final CarePlanAgentService carePlanAgentService;
    private final GeneralChatSkill generalChatSkill;
    private final ReportSummarySkill reportSummarySkill;
    private final ObservationReportIngestService observationReportIngestService;
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
        long startedAt = System.currentTimeMillis();
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
        AgentCapability routed = route(cmd);
        if (orgSession && routed != AgentCapability.GENERAL_CHAT) {
            // 机构首页会话禁止需患者的能力（方案 / OCR 等）
            routed = AgentCapability.GENERAL_CHAT;
        }
        boolean ocrAuto =
                StringUtils.hasText(cmd.imageBase64())
                        && routed == AgentCapability.GENERAL_CHAT
                        && StringUtils.hasText(cmd.peopleId());
        if (ocrAuto) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.skill("检查检验用药单识别", "自动分类识别"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("开始处理：单据识别"));
        } else {
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.skill(routed.label(), "已选择能力 · " + routed.name()));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("开始处理：" + routed.label()));
        }

        AgentSkillContext ctx = new AgentSkillContext(cmd, sessionId, history, displayName);

        AgentResponse response;
        if (ocrAuto) {
            response = streamOcrAuto(ctx, sink);
        } else if (routed == AgentCapability.CARE_PLAN) {
            response = streamCarePlan(ctx, sink);
        } else if (routed == AgentCapability.REPORT_SUMMARY) {
            response = reportSummarySkill.executeStream(ctx, sink);
        } else if (routed == AgentCapability.GENERAL_CHAT) {
            response = sink != null ? generalChatSkill.executeStream(ctx, sink) : generalChatSkill.execute(ctx);
        } else {
            // OCR 等非流式 Skill：流式通道上先推进度，再同步执行
            if (sink != null) {
                AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress(ocrProgressLabel(routed)));
                AgentStreamEvent.safeEmit(
                        sink, AgentStreamEvent.tool(routed.name(), "running", ocrProgressLabel(routed)));
            }
            long ocrStarted = System.currentTimeMillis();
            response = resolveSkill(routed, cmd).execute(ctx);
            if (sink != null) {
                AgentStreamEvent.safeEmit(
                        sink,
                        AgentStreamEvent.tool(
                                routed.name(),
                                "done",
                                "识别完成 · " + (System.currentTimeMillis() - ocrStarted) + "ms"));
            }
        }

        sessionStore.appendTurn(sessionId, cmd.message(), response.reply());
        conversationService.appendUser(sessionId, cmd.message());
        conversationService.appendAssistant(sessionId, response.reply(), response.actions());
        persistLog(cmd, sessionId, response);
        if (sink != null) {
            boolean keepExtracted =
                    AgentCapability.OCR_LAB.name().equals(response.capability())
                            || AgentCapability.OCR_EXAM.name().equals(response.capability())
                            || AgentCapability.OCR_MED.name().equals(response.capability())
                            || AgentCapability.REPORT_SUMMARY.name().equals(response.capability());
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

    private static String ocrProgressLabel(AgentCapability routed) {
        if (routed == AgentCapability.OCR_LAB) {
            return "正在识别检验单…";
        }
        if (routed == AgentCapability.OCR_EXAM) {
            return "正在识别检查单…";
        }
        if (routed == AgentCapability.OCR_MED) {
            return "正在识别用药单…";
        }
        if (routed == AgentCapability.REPORT_SUMMARY) {
            return "正在生成报告点评…";
        }
        return "正在处理…";
    }

    /** 驾驶舱通用上传：自动分类检验/检查，与专用 OCR Skill 同一流式通道。 */
    private AgentResponse streamOcrAuto(AgentSkillContext ctx, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        if (sink != null) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在识别单据…"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("OCR_AUTO", "running", "自动分类识别"));
        }
        long started = System.currentTimeMillis();
        try {
            byte[] bytes = decodeImageBase64(cmd.imageBase64());
            String mime = StringUtils.hasText(cmd.imageMimeType()) ? cmd.imageMimeType() : "image/jpeg";
            ObservationOcrPreviewDto preview =
                    observationReportIngestService.recognize(
                            cmd.tenantId(), cmd.orgId(), cmd.peopleId(), bytes, mime);
            String kind = preview.kind() == null ? "EXAM" : preview.kind().trim().toUpperCase();
            AgentCapability cap =
                    switch (kind) {
                        case "LAB" -> AgentCapability.OCR_LAB;
                        case "MED" -> AgentCapability.OCR_MED;
                        default -> AgentCapability.OCR_EXAM;
                    };
            String kindLabel =
                    switch (kind) {
                        case "LAB" -> "检验单";
                        case "MED" -> "用药单";
                        default -> "检查单";
                    };
            if (sink != null) {
                AgentStreamEvent.safeEmit(
                        sink,
                        AgentStreamEvent.tool(
                                "OCR_AUTO",
                                "done",
                                "识别为" + kindLabel + " · " + (System.currentTimeMillis() - started) + "ms"));
            }
            String title = StringUtils.hasText(preview.title()) ? preview.title() : kindLabel;
            String reply = "已识别为「" + title + "」，请在下方卡片核对后确认入库。";
            return new AgentResponse(
                    ctx.sessionId(),
                    cap.name(),
                    cap.name(),
                    reply,
                    List.of(),
                    List.of(),
                    preview);
        } catch (BusinessException e) {
            if (sink != null) {
                AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("OCR_AUTO", "done", "识别失败"));
            }
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_AUTO",
                    e.getMessage(),
                    List.of(),
                    List.of(),
                    null);
        } catch (Exception e) {
            if (sink != null) {
                AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("OCR_AUTO", "done", "识别失败"));
            }
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_AUTO",
                    "识别失败，请检查图片清晰度或稍后重试。",
                    List.of(),
                    List.of(),
                    null);
        }
    }

    private static byte[] decodeImageBase64(String imageBase64) {
        String raw = imageBase64.trim();
        int comma = raw.indexOf(',');
        if (raw.startsWith("data:") && comma > 0) {
            raw = raw.substring(comma + 1);
        }
        return Base64.getDecoder().decode(raw);
    }

    private AgentResponse streamCarePlan(AgentSkillContext ctx, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        String instruction = StringUtils.hasText(cmd.message()) ? cmd.message() : "生成管理方案";
        boolean revise = shouldReviseCarePlan(cmd);
        CarePlanBundleDto bundle;
        if (revise) {
            bundle = carePlanAgentService.reviseStream(
                    cmd.tenantId(),
                    cmd.orgId(),
                    cmd.peopleId(),
                    cmd.staffId(),
                    instruction,
                    sink);
        } else if (sink != null) {
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
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CARE_PLAN.name(),
                revise ? "CARE_PLAN_REVISE" : "CARE_PLAN_GENERATE",
                CarePlanStreamSummary.buildReply(bundle, revise),
                List.of(
                        AgentAction.openSheet("查看方案草稿", "care-plan", cmd.peopleId()),
                        AgentAction.publishCarePlan("确认发布方案", cmd.peopleId()),
                        AgentAction.triggerCapability("继续闲聊", "GENERAL_CHAT", cmd.peopleId())),
                List.of(),
                bundle);
    }

    /** 已有草稿且非「重新生成」时走修订；明确修订关键词优先。无草稿时 reviseStream 会回退生成。 */
    private static boolean shouldReviseCarePlan(AgentChatCommand cmd) {
        if (CarePlanSkill.wantsFullRegenerate(cmd.message())) {
            return false;
        }
        if (CarePlanSkill.matchesReviseMessage(cmd.message())) {
            return true;
        }
        if (CarePlanSkill.matchesGenerateMessage(cmd.message())) {
            return false;
        }
        return StringUtils.hasText(cmd.message());
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
        if (ReportSummarySkill.matchesMessage(cmd.message())) {
            return AgentCapability.REPORT_SUMMARY;
        }
        // 用药单关键词优先于检验（避免「处方」误入检验）
        if (OcrMedSkill.matchesMessage(cmd.message())) {
            return AgentCapability.OCR_MED;
        }
        // 检查单关键词优先于检验（避免「检查」误入检验）
        if (OcrExamSkill.matchesMessage(cmd.message())) {
            return AgentCapability.OCR_EXAM;
        }
        if (OcrLabSkill.matchesMessage(cmd.message())) {
            return AgentCapability.OCR_LAB;
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
