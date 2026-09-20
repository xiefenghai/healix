package com.healix.agent.tool;

import com.healix.agent.gateway.AgentAction;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import com.healix.core.cockpit.dto.CockpitPriorityCardDto;
import com.healix.core.cockpit.dto.CockpitSummaryDto;
import com.healix.core.cockpit.service.CockpitService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * B 端灵犀只读工具：焦点患者快照、机构优先名单，供通用对话接地气。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StaffBizTools {

    private final CockpitService cockpitService;

    public record FocusBundle(CockpitFocusDto focus, String text) {}

    public record OrgBriefBundle(
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top, String text, List<AgentAction> actions) {}

    public FocusBundle loadFocusContext(String tenantId, String orgId, String peopleId) {
        try {
            CockpitFocusDto focus = cockpitService.focus(tenantId, orgId, peopleId);
            return new FocusBundle(focus, formatFocus(focus));
        } catch (Exception e) {
            log.debug("loadFocusContext failed people={}", peopleId, e);
            return new FocusBundle(null, "");
        }
    }

    public OrgBriefBundle loadOrgBrief(String tenantId, String orgId, String staffId) {
        try {
            CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
            List<CockpitPriorityCardDto> top = cockpitService.topUrgent(tenantId, orgId, 5);
            List<AgentAction> actions = new ArrayList<>();
            for (CockpitPriorityCardDto c : top) {
                if (actions.size() >= 3) {
                    break;
                }
                if (!StringUtils.hasText(c.getPeopleId())) {
                    continue;
                }
                String name = StringUtils.hasText(c.getDisplayName()) ? c.getDisplayName() : "患者";
                String reason = StringUtils.hasText(c.getTopReason()) ? c.getTopReason() : "需关注";
                actions.add(AgentAction.focusPatient(name + " · " + reason, c.getPeopleId()));
            }
            actions.add(AgentAction.refresh("刷新今日建议"));
            return new OrgBriefBundle(summary, top, formatOrg(summary, top), actions);
        } catch (Exception e) {
            log.debug("loadOrgBrief failed org={}", orgId, e);
            return new OrgBriefBundle(null, List.of(), "", List.of(AgentAction.refresh("刷新今日建议")));
        }
    }

    /** 按用户话术与焦点数据给出确定性抽屉动作（不依赖模型编造 path）。 */
    public List<AgentAction> suggestPatientActions(String peopleId, String message, CockpitFocusDto focus) {
        List<AgentAction> out = new ArrayList<>();
        if (!StringUtils.hasText(peopleId)) {
            return out;
        }
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean wantFollowup = containsAny(msg, "随访", "回访", "起草随访", "写随访", "创建随访");
        boolean wantChat = containsAny(msg, "联系", "沟通", "回复患者", "回患者", "发消息", "起草沟通", "写回复");
        boolean wantObs = containsAny(msg, "指标", "血压", "血糖", "健康数据", "录入", "化验", "检验");
        boolean wantPlan = containsAny(msg, "方案", "打卡", "依从", "执行计划");
        boolean wantArchive = containsAny(msg, "档案", "完整度", "补档", "缺项");
        boolean wantAssess = containsAny(msg, "评估", "分标", "风险");
        boolean wantReport = containsAny(msg, "报告", "月报");

        if (wantFollowup) {
            out.add(AgentAction.createFollowupTask("一键创建随访待办", peopleId, "PERIODIC"));
            out.add(AgentAction.openSheet("打开随访页", "followups", peopleId));
        }
        if (wantChat) {
            if (focus != null && Boolean.TRUE.equals(focus.getClientLinked())) {
                out.add(AgentAction.nudgePatient("站内提醒患者", peopleId));
            }
            out.add(AgentAction.openSheet("联系患者", "care-chat", peopleId));
        }
        if (wantObs) {
            out.add(AgentAction.openSheet("录入健康数据", "observations", peopleId));
        }
        if (wantPlan) {
            out.add(AgentAction.openSheet("查看方案", "care-plan", peopleId));
        }
        if (wantArchive || wantAssess) {
            out.add(AgentAction.openSheet("查看档案", "archive", peopleId));
        }
        if (wantReport) {
            out.add(AgentAction.openSheet("查看报告", "reports", peopleId));
        }

        // 无明确意图时，按焦点缺口补默认动作
        if (out.isEmpty() && focus != null) {
            Integer pct = focus.getArchiveCompletenessPercent();
            if (pct != null && pct < 80) {
                out.add(AgentAction.openSheet("补全档案", "archive", peopleId));
            }
            Double rate = focus.getPlanRate7d();
            if (rate != null && rate < 0.7) {
                out.add(AgentAction.openSheet("查看方案依从", "care-plan", peopleId));
                if (Boolean.TRUE.equals(focus.getClientLinked())) {
                    out.add(AgentAction.nudgePatient("提醒补打卡", peopleId));
                }
            }
            if (focus.getOpenTasks() != null && !focus.getOpenTasks().isEmpty()) {
                String type = focus.getOpenTasks().get(0).getTaskType();
                if ("FOLLOW_UP".equals(type) || "PLAN_NUDGE".equals(type)) {
                    out.add(AgentAction.createFollowupTask("创建随访待办", peopleId, "PERIODIC"));
                    out.add(AgentAction.openSheet("处理随访", "followups", peopleId));
                } else if ("PLAN_CREATE".equals(type) || "PLAN_REVIEW".equals(type)) {
                    out.add(AgentAction.openSheet("处理方案待办", "care-plan", peopleId));
                } else if ("REPORT_REVIEW".equals(type)) {
                    out.add(AgentAction.openSheet("查看报告", "reports", peopleId));
                } else if ("METRIC_ALERT".equals(type)) {
                    out.add(AgentAction.openSheet("录入健康数据", "observations", peopleId));
                } else {
                    out.add(AgentAction.openSheet("查看档案", "archive", peopleId));
                }
            }
            if (Boolean.TRUE.equals(focus.getClientLinked())) {
                out.add(AgentAction.nudgePatient("站内提醒患者", peopleId));
            }
            out.add(AgentAction.openSheet("联系患者", "care-chat", peopleId));
        }

        return limit(out, 5);
    }

    public static boolean wantsFollowupDraft(String message) {
        return containsAny(
                message == null ? "" : message.toLowerCase(Locale.ROOT),
                "起草随访",
                "写随访",
                "随访草稿",
                "帮我写随访",
                "生成随访");
    }

    public static boolean wantsChatDraft(String message) {
        return containsAny(
                message == null ? "" : message.toLowerCase(Locale.ROOT),
                "回复患者",
                "回患者",
                "起草沟通",
                "写回复",
                "帮我回",
                "沟通草稿");
    }

    private static String formatFocus(CockpitFocusDto f) {
        if (f == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("患者：").append(nullToDash(f.getDisplayName()));
        if (f.getClientLinked() != null) {
            sb.append("；C端：").append(Boolean.TRUE.equals(f.getClientLinked()) ? "已激活" : "未激活");
        }
        if (f.getArchiveCompletenessPercent() != null) {
            sb.append("；档案完整度：")
                    .append(f.getArchiveCompletenessPercent())
                    .append("%");
            if (f.getArchiveFilledCount() != null && f.getArchiveTotalCount() != null) {
                sb.append("（")
                        .append(f.getArchiveFilledCount())
                        .append("/")
                        .append(f.getArchiveTotalCount())
                        .append("）");
            }
        }
        if (f.getPlanRate7d() != null) {
            sb.append("；近7日依从：").append(Math.round(f.getPlanRate7d() * 100)).append("%");
        }
        if (StringUtils.hasText(f.getBloodPressure())) {
            sb.append("；血压：").append(f.getBloodPressure());
        }
        if (f.getAssessmentTags() != null && !f.getAssessmentTags().isEmpty()) {
            List<String> tags = new ArrayList<>();
            for (CockpitFocusDto.AssessmentTag t : f.getAssessmentTags()) {
                if (t != null && StringUtils.hasText(t.getText())) {
                    tags.add(t.getText());
                }
            }
            if (!tags.isEmpty()) {
                sb.append("；评估标签：").append(String.join("、", tags));
            }
        }
        if (f.getDiseaseLabels() != null && !f.getDiseaseLabels().isEmpty()) {
            sb.append("；病种：").append(String.join("、", f.getDiseaseLabels()));
        }
        if (f.getOpenTasks() != null && !f.getOpenTasks().isEmpty()) {
            sb.append("；开放任务：");
            int n = Math.min(5, f.getOpenTasks().size());
            for (int i = 0; i < n; i++) {
                CockpitFocusDto.OpenTaskBrief t = f.getOpenTasks().get(i);
                if (i > 0) {
                    sb.append("；");
                }
                sb.append(nullToDash(t.getTaskTypeLabel()));
                if (StringUtils.hasText(t.getSummary())) {
                    sb.append("（").append(t.getSummary()).append("）");
                }
                if (t.isOverdue()) {
                    sb.append("[超期]");
                }
            }
        }
        return sb.toString();
    }

    private static String formatOrg(CockpitSummaryDto summary, List<CockpitPriorityCardDto> top) {
        StringBuilder sb = new StringBuilder();
        if (summary != null) {
            sb.append("今日待办 ")
                    .append(summary.getOpenTaskCount())
                    .append("，超期 ")
                    .append(summary.getOverdueCount())
                    .append("。");
        }
        if (top == null || top.isEmpty()) {
            sb.append("当前优先名单为空。");
            return sb.toString();
        }
        sb.append("优先对象：");
        for (int i = 0; i < top.size(); i++) {
            CockpitPriorityCardDto c = top.get(i);
            if (i > 0) {
                sb.append("；");
            }
            sb.append(nullToDash(c.getDisplayName()));
            if (StringUtils.hasText(c.getTopReason())) {
                sb.append("（").append(c.getTopReason()).append("）");
            }
        }
        sb.append("。");
        return sb.toString();
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<AgentAction> limit(List<AgentAction> list, int max) {
        if (list.size() <= max) {
            return list;
        }
        return new ArrayList<>(list.subList(0, max));
    }

    private static String nullToDash(String s) {
        return StringUtils.hasText(s) ? s : "-";
    }
}
