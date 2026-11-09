package com.healix.agent.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.report.ReportSummaryGenerator;
import com.healix.agent.report.ReportSummaryGenerator.ReportSummaryDraft;
import com.healix.agent.report.ReportSummaryGenerator.ReviseContext;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.exception.BusinessException;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.report.catalog.HealthReportGeneratedBy;
import com.healix.core.report.catalog.HealthReportPeriodType;
import com.healix.core.report.catalog.HealthReportStatus;
import com.healix.core.report.dto.HealthReportListItemDto;
import com.healix.core.report.dto.HealthReportViewDto;
import com.healix.core.report.service.HealthReportService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话内管理报告（由 StaffAgentTools 调用）。
 */
@Component
@RequiredArgsConstructor
public class ReportSummaryHandler {

    private final HealthReportService healthReportService;
    private final ReportSummaryGenerator reportSummaryGenerator;
    private final FeatureFlagService featureFlagService;

    public enum ReviseMode {
        /** 有点评草稿且有跟进话术 → 修订，否则新生成 */
        AUTO,
        FORCE_GENERATE,
        FORCE_REVISE
    }

    public AgentResponse execute(AgentHandlerContext ctx) {
        return executeStream(ctx, null, ReviseMode.AUTO);
    }

    public AgentResponse executeStream(AgentHandlerContext ctx, Consumer<AgentStreamEvent> sink) {
        return executeStream(ctx, sink, ReviseMode.AUTO);
    }

    public AgentResponse executeStream(
            AgentHandlerContext ctx, Consumer<AgentStreamEvent> sink, ReviseMode mode) {
        AgentChatCommand cmd = ctx.command();
        if (!StringUtils.hasText(cmd.peopleId())) {
            return fail(ctx, "请先选择患者，再生成管理报告或点评。", List.of());
        }

        HealthReportViewDto report = null;
        boolean newlyGenerated = false;
        HealthReportListItemDto draftItem = findLatestDraft(cmd.orgId(), cmd.peopleId());
        if (draftItem != null) {
            report = healthReportService.get(cmd.orgId(), draftItem.getId());
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("已找到报告草稿，准备点评…"));
        } else {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在生成最近周期的管理报告草稿…"));
            EnsureResult ensured = ensureDraftReport(cmd);
            if (ensured.report() == null) {
                return fail(
                        ctx,
                        ensured.message(),
                        List.of(
                                AgentAction.openSheet("打开报告页", "reports", cmd.peopleId()),
                                AgentAction.setCockpitTab("回到我的关注", "watch")));
            }
            report = ensured.report();
            newlyGenerated = true;
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("generateReport", "done", "报告草稿已就绪"));
        }

        try {
            featureFlagService.assertEnabled(cmd.tenantId(), FeatureFlagKeyEnum.AI_REPORT_SUMMARY);
        } catch (BusinessException e) {
            List<AgentAction> actions = new ArrayList<>();
            actions.add(AgentAction.openSheet("打开报告页", "reports", cmd.peopleId()));
            String prefix = newlyGenerated ? "已生成报告草稿，但点评未开启：" : "";
            return fail(ctx, prefix + e.getMessage(), actions);
        }

        boolean revise = resolveRevise(mode, cmd, report);
        ReviseContext reviseCtx = null;
        if (revise) {
            reviseCtx = new ReviseContext(
                    cmd.message(),
                    currentStaffComment(report),
                    currentNextFocus(report),
                    currentQuarterAdvice(report));
            if (!StringUtils.hasText(reviseCtx.staffComment()) && !StringUtils.hasText(reviseCtx.nextFocus())) {
                revise = false;
                reviseCtx = null;
                AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("当前无点评草稿，改为新生成…"));
            }
        }

        ReportSummaryDraft draft = reportSummaryGenerator.generate(
                report.getContent(), report.getPeriodType(), sink, reviseCtx);

        // 落库草稿寄语，便于对话多轮修订（不发布）
        try {
            report = healthReportService.saveNarrativeDraft(
                    cmd.orgId(),
                    report.getId(),
                    cmd.staffId(),
                    draft.staffComment(),
                    draft.nextFocus(),
                    draft.quarterAdvice(),
                    draft.insights(),
                    draft.highlights());
        } catch (BusinessException e) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("点评已生成，但草稿暂未写入：" + e.getMessage()));
        } catch (Exception e) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("点评已生成，草稿写入失败，仍可点审阅按钮使用。"));
        }

        String reply = buildReply(report, draft, newlyGenerated, revise);
        List<AgentAction> actions = new ArrayList<>();
        actions.add(
                AgentAction.openReportReview(
                        "使用点评草稿审阅",
                        cmd.peopleId(),
                        report.getId(),
                        draft.staffComment(),
                        draft.nextFocus(),
                        draft.quarterAdvice()));
        actions.add(
                AgentAction.publishReport(
                        "确认发布给患者",
                        cmd.peopleId(),
                        report.getId(),
                        draft.staffComment(),
                        draft.nextFocus(),
                        draft.quarterAdvice()));
        actions.add(AgentAction.openSheet("打开报告列表", "reports", cmd.peopleId()));
        actions.add(AgentAction.setCockpitTab("回到我的关注", "watch"));
        actions.add(AgentAction.triggerCapability("继续闲聊", "GENERAL_CHAT", cmd.peopleId()));

        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.REPORT_SUMMARY.name(),
                revise ? "REPORT_SUMMARY_REVISE" : "REPORT_SUMMARY",
                reply,
                actions,
                List.of(),
                toExtracted(report, draft, newlyGenerated, revise));
    }

    /** 不靠关键词：强制模式优先；AUTO 时看是否已有点评草稿。 */
    static boolean resolveRevise(ReviseMode mode, AgentChatCommand cmd, HealthReportViewDto report) {
        if (mode == ReviseMode.FORCE_GENERATE) {
            return false;
        }
        if (mode == ReviseMode.FORCE_REVISE) {
            return true;
        }
        boolean hasComment = StringUtils.hasText(currentStaffComment(report))
                || StringUtils.hasText(currentNextFocus(report));
        return hasComment && StringUtils.hasText(cmd.message());
    }

    private EnsureResult ensureDraftReport(AgentChatCommand cmd) {
        HealthReportPeriodType periodType = HealthReportPeriodType.WEEK;
        try {
            HealthReportViewDto created =
                    healthReportService.generate(
                            cmd.tenantId(),
                            cmd.orgId(),
                            cmd.peopleId(),
                            periodType.name(),
                            null,
                            HealthReportGeneratedBy.MANUAL.name(),
                            cmd.staffId(),
                            false);
            if (created == null) {
                return new EnsureResult(
                        null,
                        "暂无法生成"
                                + periodType.label()
                                + "。常见原因：周期未结束、入组未满一周、或数据不足。请打开报告页核对后再试。");
            }
            return new EnsureResult(created, null);
        } catch (BusinessException e) {
            return new EnsureResult(null, humanizeGenerateFailure(periodType, e.getMessage()));
        }
    }

    private static String humanizeGenerateFailure(HealthReportPeriodType periodType, String raw) {
        String msg = raw == null ? "" : raw.trim();
        String period = periodType.label();
        if (msg.contains("入组未满一周") || msg.contains("入组日")) {
            return "未能生成" + period + "：" + msg + "。可改出月报/季报，或等入组满一周后再试。";
        }
        if (msg.contains("无法确定已结束") || msg.contains("周期须为已结束") || msg.contains("周期已结束")) {
            return "未能生成" + period + "：尚无已结束的对应周期。请等本周/本月/本季结束后再出报，或打开报告页手工选周期。";
        }
        if (msg.contains("已存在报告") || msg.contains("已结案")) {
            return "未能生成" + period + "：" + msg + "。可打开报告页查看已有草稿或已发布报告。";
        }
        if (msg.contains("未在主管机构入组") || msg.contains("无主管机构") || msg.contains("仅主管机构")) {
            return "未能生成" + period + "：" + msg + "。请先确认患者已入组且当前机构为主管机构。";
        }
        if (msg.contains("数据不足")
                || msg.contains("阈值")
                || msg.contains("观测")
                || msg.contains("不出报")
                || msg.contains("应打")
                || msg.contains("应服")
                || msg.contains("应执行")) {
            return "未能生成" + period + "：" + msg + "。可先补齐方案执行/用药/随访数据后再出报。";
        }
        if (!msg.isEmpty()) {
            return "未能自动生成" + period + "：" + msg + "。可打开报告页核对入组与周期后再试。";
        }
        return "未能自动生成" + period + "。可打开报告页核对入组与周期后再试。";
    }

    private HealthReportListItemDto findLatestDraft(String orgId, String peopleId) {
        for (HealthReportListItemDto item : healthReportService.listByPeople(orgId, peopleId, 20)) {
            if (item != null && HealthReportStatus.DRAFT.matches(item.getStatus())) {
                return item;
            }
        }
        return null;
    }

    private static String currentStaffComment(HealthReportViewDto report) {
        if (report == null) {
            return null;
        }
        if (StringUtils.hasText(report.getStaffComment())) {
            return report.getStaffComment().trim();
        }
        return narrativeText(report, "staffComment");
    }

    private static String currentNextFocus(HealthReportViewDto report) {
        return narrativeText(report, "nextFocus");
    }

    private static String currentQuarterAdvice(HealthReportViewDto report) {
        return narrativeText(report, "quarterAdvice");
    }

    private static String narrativeText(HealthReportViewDto report, String field) {
        if (report == null || report.getContent() == null) {
            return null;
        }
        JsonNode nar = report.getContent().get("narrative");
        if (nar == null || !nar.isObject()) {
            return null;
        }
        JsonNode v = nar.get(field);
        if (v == null || v.isNull() || !StringUtils.hasText(v.asText())) {
            return null;
        }
        return v.asText().trim();
    }

    private static String buildReply(
            HealthReportViewDto report, ReportSummaryDraft draft, boolean newlyGenerated, boolean revised) {
        StringBuilder sb = new StringBuilder();
        String title = StringUtils.hasText(report.getTitle()) ? report.getTitle() : "管理报告";
        if (revised) {
            sb.append("已按你的要求修订点评草稿");
        } else if (newlyGenerated) {
            sb.append("已生成报告草稿并完成点评");
        } else {
            sb.append("已生成点评草稿");
        }
        sb.append('\n').append(title);
        List<String> meta = new ArrayList<>();
        if (StringUtils.hasText(report.getPeriodTypeLabel())) {
            meta.add(report.getPeriodTypeLabel());
        }
        meta.add(draft.fromLlm() ? "AI 生成" : "模板草稿");
        if (!draft.fromLlm() && StringUtils.hasText(draft.note())) {
            meta.add(draft.note());
        }
        if (!meta.isEmpty()) {
            sb.append('\n').append(String.join(" · ", meta));
        }

        if (StringUtils.hasText(draft.staffComment())) {
            sb.append("\n\n健管师寄语\n").append(draft.staffComment().trim());
        }
        if (StringUtils.hasText(draft.nextFocus())) {
            sb.append("\n\n下阶段关注\n").append(formatFocusLines(draft.nextFocus()));
        }
        if (StringUtils.hasText(draft.quarterAdvice())) {
            sb.append("\n\n阶段建议\n").append(draft.quarterAdvice().trim());
        }
        sb.append("\n\n可直接在对话里说「寄语写短一点」继续改；或点「使用点评草稿审阅」核对后发布。");
        return sb.toString();
    }

    private static String formatFocusLines(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.replace('\r', '\n').trim();
        if (text.contains("\n")) {
            return text.replaceAll("\n{3,}", "\n\n").trim();
        }
        String spaced = text
                .replaceAll("([；;。])\\s*(?=\\d+[、.．)])", "$1\n")
                .replaceAll("(?<!^|\\n)\\s*(?=\\d+[、.．)])", "\n")
                .trim();
        return spaced.replaceAll("\n{3,}", "\n\n");
    }

    private static Map<String, Object> toExtracted(
            HealthReportViewDto report, ReportSummaryDraft draft, boolean newlyGenerated, boolean revised) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("reportId", report.getId());
        map.put("reportTitle", report.getTitle());
        map.put("periodType", report.getPeriodType());
        map.put("periodTypeLabel", report.getPeriodTypeLabel());
        map.put("staffComment", draft.staffComment());
        map.put("nextFocus", formatFocusLines(draft.nextFocus()));
        map.put("quarterAdvice", draft.quarterAdvice());
        map.put("fromLlm", draft.fromLlm());
        map.put("note", draft.note());
        map.put("newlyGenerated", newlyGenerated);
        map.put("revised", revised);
        return map;
    }

    private static AgentResponse fail(AgentHandlerContext ctx, String reply, List<AgentAction> actions) {
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.REPORT_SUMMARY.name(),
                "REPORT_SUMMARY",
                reply,
                actions,
                List.of(),
                null);
    }

    private record EnsureResult(HealthReportViewDto report, String message) {}
}
