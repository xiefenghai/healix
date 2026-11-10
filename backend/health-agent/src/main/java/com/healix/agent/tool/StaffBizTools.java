package com.healix.agent.tool;

import com.healix.agent.gateway.AgentAction;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import com.healix.core.cockpit.dto.CockpitPriorityCardDto;
import com.healix.core.cockpit.dto.CockpitSummaryDto;
import com.healix.core.cockpit.service.CockpitService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final PatientBizTools patientBizTools;

    public record FocusBundle(CockpitFocusDto focus, String text) {}

    public record OrgBriefBundle(
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top, String text, List<AgentAction> actions) {}

    public FocusBundle loadFocusContext(String tenantId, String orgId, String peopleId) {
        try {
            CockpitFocusDto focus = cockpitService.focus(tenantId, orgId, peopleId);
            if (focus != null) {
                focus.setLifestyleSummary(lifestyleText(tenantId, peopleId));
            }
            return new FocusBundle(focus, formatFocus(focus));
        } catch (Exception e) {
            log.debug("loadFocusContext failed people={}", peopleId, e);
            return new FocusBundle(null, "");
        }
    }

    public OrgBriefBundle loadOrgBrief(String tenantId, String orgId, String staffId) {
        return loadOrgBrief(tenantId, orgId, staffId, null);
    }

    /** @param message 用户原话；含分组/批量等词时即使暂无未分组也会挂面板入口，方便「怎么操作」类问答 */
    public OrgBriefBundle loadOrgBrief(String tenantId, String orgId, String staffId, String message) {
        try {
            CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
            List<CockpitPriorityCardDto> top = cockpitService.topUrgent(tenantId, orgId, 5);
            List<AgentAction> actions = new ArrayList<>();
            boolean noPrimaryTeam = summary != null && summary.getPrimaryCareTeamCount() <= 0;
            int unassigned = summary == null ? 0 : Math.max(0, summary.getUnassignedPatientCount());
            boolean batchHowTo = asksBatchAssign(message);
            boolean careTeamHowTo = asksCareTeamGuide(message);
            boolean createTeamIntent = asksCreateCareTeamIntent(message);
            String suggestedTeamName = extractSuggestedCareTeamName(message);
            if (noPrimaryTeam) {
                // 未建组：建组可点；批量分配面板一并挂出但置灰（防呆：没组无法分配）
                actions.add(
                        AgentAction.createCareTeam(
                                "去创建我的健管组", suggestedTeamName));
                if (unassigned > 0 || batchHowTo || careTeamHowTo) {
                    actions.add(AgentAction.openUnassignedWorkbench("打开批量分配面板", true));
                }
            } else {
                // 已有组：用户明确要再建组时挂创建入口（支持多组）；有组名则预填
                if (createTeamIntent) {
                    actions.add(
                            AgentAction.createCareTeam(
                                    StringUtils.hasText(suggestedTeamName) ? "打开创建面板" : "去创建健管组",
                                    suggestedTeamName));
                }
                if (unassigned > 0 || batchHowTo) {
                    actions.add(AgentAction.openUnassignedWorkbench("打开批量分配面板"));
                }
                // 「怎么批量分组」或「再建组」类问答勿再塞优先名单姓名 chip
                if (!batchHowTo && !createTeamIntent) {
                    for (CockpitPriorityCardDto c : top) {
                        if (actions.size() >= 5) {
                            break;
                        }
                        if (!StringUtils.hasText(c.getPeopleId())) {
                            continue;
                        }
                        String name = StringUtils.hasText(c.getDisplayName()) ? c.getDisplayName() : "患者";
                        String reason = StringUtils.hasText(c.getTopReason()) ? c.getTopReason() : "需关注";
                        actions.add(AgentAction.focusPatient(name + " · " + reason, c.getPeopleId()));
                    }
                }
            }
            String text = formatOrg(summary, top);
            return new OrgBriefBundle(summary, top, text, actions);
        } catch (Exception e) {
            log.debug("loadOrgBrief failed org={}", orgId, e);
            return new OrgBriefBundle(null, List.of(), "", List.of());
        }
    }

    /** 用户在问批量分组/未分组怎么处理时，即便当前 0 人未分组也提供面板入口。 */
    static boolean asksBatchAssign(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String m = message.trim();
        return m.contains("批量分组")
                || m.contains("批量分配")
                || m.contains("待分组")
                || m.contains("未分组")
                || m.contains("还没分组")
                || (m.contains("分组") && (m.contains("批量") || m.contains("怎么") || m.contains("如何")));
    }

    /** 问健管组是什么/为什么要建/怎么分组：未建组时一并挂建组 + 批量面板入口。 */
    static boolean asksCareTeamGuide(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String m = message.trim();
        return m.contains("健管组")
                || m.contains("为什么要创建")
                || m.contains("怎么给患者分组")
                || m.contains("如何给患者分组")
                || (m.contains("分组") && (m.contains("怎么") || m.contains("如何") || m.contains("为什么")));
    }

    /**
     * 明确要创建/再建健管组（含已有组后再建）。
     * 覆盖「建组」「建组 健管二组」「帮我建组」等；排除「怎么建组」「建组后怎么…」问答。
     */
    public static boolean asksCreateCareTeamIntent(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String m = message.trim();
        // 问答/说明：不打开创建面板
        if (m.matches("^(怎么|如何|怎样|为何|为什么|啥是|什么是).*")) {
            return false;
        }
        if (m.contains("建组后")
                || m.contains("创建组后")
                || m.contains("创建健管组后")
                || m.contains("之后怎么")
                || m.contains("之后如何")
                || m.contains("是什么意思")
                || m.contains("干什么的")) {
            return false;
        }
        // 「建/创建/新建 … 组」即可（可带组名），避免只认整句短口令
        if (m.matches(
                ".*(?:帮我|请|我想|我要|麻烦|去)?"
                        + "(?:再)?"
                        + "(?:建|创建|新建)"
                        + "(?:一个|个)?"
                        + "(?:健管)?"
                        + "组.*")) {
            return true;
        }
        return m.contains("再建一个组")
                || m.contains("再建一个健管组")
                || m.contains("再创建一个组")
                || m.contains("再创建一个健管组")
                || m.contains("再建组")
                || m.contains("再创建健管组")
                || m.contains("新建健管组")
                || m.contains("新建一个健管组")
                || m.contains("新建组")
                || m.contains("创建健管组")
                || m.contains("创建一个健管组")
                || m.contains("加一个健管组")
                || m.contains("再开一个组")
                || m.contains("帮我建组")
                || m.contains("我要建组")
                || m.contains("去建组");
    }

    private static final Pattern CARE_TEAM_NAME_QUOTED =
            Pattern.compile("(?:再)?(?:建|创建|新建)(?:一个|个)?(?:健管)?组\\s*[「『\"']([^」』\"']+)[」』\"']");
    private static final Pattern CARE_TEAM_NAME_CALLED =
            Pattern.compile("(?:名叫|叫作?|名称[是为：:\\s]*|名为)\\s*[「『\"']?([^」』\"'\\s，,。！？!?]+)[」』\"']?");
    private static final Pattern CARE_TEAM_NAME_AFTER =
            Pattern.compile("(?:再)?(?:建|创建|新建)(?:一个|个)?(?:健管)?组\\s*[：:，,、]?\\s*(.+)$");
    private static final Pattern CARE_TEAM_NAME_AFTER_SHORT =
            Pattern.compile("再建(?:一个)?组\\s*[：:，,、]?\\s*(.+)$");

    /**
     * 从「建组 健管二组」「创建健管组 健管二组」「新建组叫XX」等话术提取预填组名；无明确名称时返回 null。
     */
    public static String extractSuggestedCareTeamName(String message) {
        if (!asksCreateCareTeamIntent(message)) {
            return null;
        }
        String m = message.trim();
        Matcher quoted = CARE_TEAM_NAME_QUOTED.matcher(m);
        if (quoted.find()) {
            String n = sanitizeSuggestedCareTeamName(quoted.group(1));
            if (n != null) {
                return n;
            }
        }
        Matcher named = CARE_TEAM_NAME_CALLED.matcher(m);
        if (named.find()) {
            String n = sanitizeSuggestedCareTeamName(named.group(1));
            if (n != null) {
                return n;
            }
        }
        Matcher after = CARE_TEAM_NAME_AFTER.matcher(m);
        if (after.find()) {
            String n = sanitizeSuggestedCareTeamName(after.group(1));
            if (n != null) {
                return n;
            }
        }
        Matcher afterShort = CARE_TEAM_NAME_AFTER_SHORT.matcher(m);
        if (afterShort.find()) {
            return sanitizeSuggestedCareTeamName(afterShort.group(1));
        }
        return null;
    }

    static String sanitizeSuggestedCareTeamName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String name =
                raw.trim()
                        .replaceAll("^[「『\"']+|[」』\"']+$", "")
                        .replaceAll("^(叫|名叫|叫作|名称是|名称为|名为)", "")
                        .replaceAll("[。！？!?，,；;]+$", "")
                        .trim();
        if (!StringUtils.hasText(name)) {
            return null;
        }
        if (name.matches("^(吧|啊|呀|呢|吗|嘛|谢谢|一下|一个|帮我|请|的)$")) {
            return null;
        }
        if (name.contains("健管组")
                || name.contains("创建")
                || name.contains("新建")
                || name.contains("再建")
                || name.contains("分配")
                || name.contains("患者")) {
            return null;
        }
        if (name.length() > 20) {
            return null;
        }
        return name;
    }

    private static AgentAction trendAction(String label, String peopleId) {
        return AgentAction.openSheet(label, "observations", peopleId, Map.of("sub", "trends"));
    }

    /**
     * 按焦点数据给出确定性抽屉动作（不靠用户话术关键词猜意图）。
     */
    public List<AgentAction> suggestPatientActions(String peopleId, CockpitFocusDto focus) {
        List<AgentAction> out = new ArrayList<>();
        if (!StringUtils.hasText(peopleId)) {
            return out;
        }
        boolean needTeam = needsCareTeamAssign(focus);
        if (needTeam) {
            out.add(AgentAction.navigate("去分组", "join-care-team", peopleId));
            out.add(AgentAction.createCareTeam("去创建健管组", null));
        }

        if (focus != null) {
            Integer pct = focus.getArchiveCompletenessPercent();
            if (pct != null && pct < 80) {
                out.add(AgentAction.openSheet("补全档案", "archive", peopleId));
            }
            if (focus.getAssessmentTags() != null && !focus.getAssessmentTags().isEmpty()) {
                out.add(AgentAction.openSheet("查看评估标签", "assessments", peopleId));
            }
            if (focus.isMedIncomplete()) {
                out.add(AgentAction.openSheet("核对今日用药", "medications", peopleId));
                if (Boolean.TRUE.equals(focus.getClientLinked())) {
                    out.add(AgentAction.nudgePatient("提醒补服药", peopleId));
                }
            }
            Double rate = focus.getPlanRate7d();
            if (rate != null && rate < 0.7) {
                out.add(AgentAction.openSheet("查看方案依从", "care-plan", peopleId));
                if (Boolean.TRUE.equals(focus.getClientLinked())) {
                    out.add(AgentAction.nudgePatient("提醒补打卡", peopleId));
                }
            }
            if (focus.getOpenTasks() != null && !focus.getOpenTasks().isEmpty()) {
                // 未分组时优先扫 TEAM_ASSIGN，勿被首诊/随访待办抢走入口
                CockpitFocusDto.OpenTaskBrief teamTask = null;
                for (CockpitFocusDto.OpenTaskBrief t : focus.getOpenTasks()) {
                    if (t != null && "TEAM_ASSIGN".equals(t.getTaskType())) {
                        teamTask = t;
                        break;
                    }
                }
                var top = teamTask != null ? teamTask : focus.getOpenTasks().get(0);
                appendTaskCloseActions(out, peopleId, top);
                String type = top.getTaskType();
                if (!needTeam && ("FOLLOW_UP".equals(type) || "PLAN_NUDGE".equals(type))) {
                    out.add(AgentAction.createFollowupTask("一键创建随访待办", peopleId, "ROUTINE"));
                } else if ("PLAN_CREATE".equals(type) || "PLAN_REVIEW".equals(type)) {
                    if (Boolean.TRUE.equals(focus.getIntakeAwaitingStaffConfirm())
                            || (top.getTaskTypeLabel() != null
                                    && top.getTaskTypeLabel().contains("待确认"))) {
                        out.add(AgentAction.openSheet("打开问卷并确认评估", "intake", peopleId));
                    } else {
                        out.add(AgentAction.triggerCapability("一键生成方案草稿", "CARE_PLAN", peopleId));
                        out.add(AgentAction.openSheet("处理方案待办", "care-plan", peopleId));
                    }
                } else if ("REPORT_REVIEW".equals(type)) {
                    out.add(AgentAction.triggerCapability("一键生成报告点评", "REPORT_SUMMARY", peopleId));
                    out.add(AgentAction.openSheet("审阅管理报告", "reports", peopleId));
                } else if ("METRIC_ALERT".equals(type)) {
                    String summary = top.getSummary() == null ? "" : top.getSummary();
                    out.add(AgentAction.createFollowupTask("一键创建随访待办", peopleId, "ROUTINE"));
                    out.add(trendAction(
                            summary.contains("血压") ? "看血压趋势" : summary.contains("血糖") ? "看血糖趋势" : "看指标趋势",
                            peopleId));
                } else if ("TEAM_ASSIGN".equals(type)) {
                    if (!hasNavigate(out, "join-care-team", peopleId)) {
                        out.add(AgentAction.navigate("去分配健管组", "join-care-team", peopleId));
                    }
                } else if (!StringUtils.hasText(type)) {
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

    /** 焦点处于「分组」环节或尚未入组。 */
    static boolean needsCareTeamAssign(CockpitFocusDto focus) {
        if (focus == null) {
            return false;
        }
        if (Boolean.TRUE.equals(focus.getNeedsCareTeamAssign())) {
            return true;
        }
        if ("TEAM_ASSIGN".equalsIgnoreCase(focus.getServiceStageCode())) {
            return true;
        }
        return !StringUtils.hasText(focus.getCareTeamId())
                && focus.getNeedsCareTeamAssign() != Boolean.FALSE;
    }

    /**
     * 为 OPEN 待办挂「确认办结」或打开业务页。
     * 驾驶舱「立即处理」池内任务已归属当前健管师，不挂「领取」。
     * PLAN_NUDGE / METRIC_ALERT / FOLLOW_UP → COMPLETE_TASK（前端打开与工作台一致的填单弹窗）。
     */
    private static boolean appendTaskCloseActions(
            List<AgentAction> out, String peopleId, CockpitFocusDto.OpenTaskBrief top) {
        if (top == null || !StringUtils.hasText(top.getId())) {
            return false;
        }
        String type = top.getTaskType() == null ? "" : top.getTaskType();
        String typeLabel =
                StringUtils.hasText(top.getTaskTypeLabel()) ? top.getTaskTypeLabel() : type;
        if ("PLAN_NUDGE".equals(type) || "METRIC_ALERT".equals(type) || "FOLLOW_UP".equals(type)) {
            // 首诊走问卷工作台，文案用「开始首诊」而非「确认办结」
            boolean onboarding = "ONBOARDING".equalsIgnoreCase(
                    StringUtils.hasText(top.getFollowupType()) ? top.getFollowupType() : "");
            if (!onboarding && StringUtils.hasText(typeLabel) && typeLabel.contains("首诊")) {
                onboarding = true;
            }
            String label = onboarding
                    ? "开始首诊" + summarySuffix(top.getSummary(), typeLabel)
                    : completeTaskLabel(typeLabel, top.getSummary());
            out.add(AgentAction.completeTask(label, peopleId, top.getId(), type, top.getSummary()));
            return true;
        }
        if ("PLAN_CREATE".equals(type) || "PLAN_REVIEW".equals(type)) {
            out.add(AgentAction.openSheet(
                    "处理方案待办「" + typeLabel + "」" + summarySuffix(top.getSummary(), typeLabel),
                    "care-plan",
                    peopleId));
            return true;
        }
        if ("REPORT_REVIEW".equals(type)) {
            out.add(AgentAction.openSheet(
                    "审阅管理报告「" + typeLabel + "」" + summarySuffix(top.getSummary(), typeLabel),
                    "reports",
                    peopleId));
            return true;
        }
        if ("TEAM_ASSIGN".equals(type)) {
            if (hasNavigate(out, "join-care-team", peopleId)) {
                return true;
            }
            out.add(AgentAction.navigate(
                    "去分配健管组「" + typeLabel + "」" + summarySuffix(top.getSummary(), typeLabel),
                    "join-care-team",
                    peopleId));
            return true;
        }
        if ("ASSESSMENT_ESCALATION".equals(type) || "QUESTIONNAIRE_EXPIRED".equals(type)) {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("taskId", top.getId());
            out.add(AgentAction.callApi(
                    "确认办结「" + typeLabel + "」" + summarySuffix(top.getSummary(), typeLabel),
                    "ACK_TASK",
                    peopleId,
                    ack));
            if (!hasOpenSheet(out, "assessments", peopleId)) {
                out.add(AgentAction.openSheet("查看评估", "assessments", peopleId));
            }
            return true;
        }
        if ("ASSESSMENT_SUSPECTED".equals(type)) {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("taskId", top.getId());
            out.add(AgentAction.openSheet(
                    "去评估建档「" + typeLabel + "」" + summarySuffix(top.getSummary(), typeLabel),
                    "assessments",
                    peopleId));
            out.add(AgentAction.callApi("暂不建档，办结", "ACK_TASK", peopleId, ack));
            return true;
        }
        return false;
    }

    private static boolean hasOpenSheet(List<AgentAction> out, String mode, String peopleId) {
        for (AgentAction a : out) {
            if (a != null
                    && "OPEN_SHEET".equals(a.type())
                    && mode.equals(a.path())
                    && eqNullable(peopleId, a.peopleId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNavigate(List<AgentAction> out, String path, String peopleId) {
        for (AgentAction a : out) {
            if (a != null
                    && "NAVIGATE".equals(a.type())
                    && path.equals(a.path())
                    && eqNullable(peopleId, a.peopleId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean eqNullable(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    /** 同类型多条待办时用摘要区分按钮文案，避免去重后只剩一个。 */
    private static String completeTaskLabel(String typeLabel, String summary) {
        String base = "确认办结「" + typeLabel + "」";
        String suffix = summarySuffix(summary, typeLabel);
        return StringUtils.hasText(suffix) ? base + suffix : base;
    }

    private static String summarySuffix(String summary, String typeLabel) {
        if (!StringUtils.hasText(summary)) {
            return "";
        }
        String s = summary.trim().replace('\n', ' ');
        if (s.equals(typeLabel)) {
            return "";
        }
        if (s.startsWith(typeLabel + " · ") || s.startsWith(typeLabel + "·")) {
            s = s.substring(typeLabel.length()).replaceFirst("^[·\\s]+", "").trim();
        }
        if (!StringUtils.hasText(s)) {
            return "";
        }
        if (s.length() > 20) {
            s = s.substring(0, 20) + "…";
        }
        return " · " + s;
    }

    private String lifestyleText(String tenantId, String peopleId) {
        List<String> parts = new ArrayList<>();
        for (PatientBizTools.ToolOutput out : List.of(
                patientBizTools.recentMeals(tenantId, peopleId),
                patientBizTools.recentExercise(tenantId, peopleId))) {
            if (StringUtils.hasText(out.text())) {
                parts.add(out.text());
            }
        }
        return String.join("；", parts);
    }

    private static String formatFocus(CockpitFocusDto f) {
        if (f == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("患者：").append(nullToDash(f.getDisplayName()));
        if (StringUtils.hasText(f.getServiceStageLabel())) {
            sb.append("；服务环节：").append(f.getServiceStageLabel());
        }
        if (Boolean.TRUE.equals(f.getNeedsCareTeamAssign()) || !StringUtils.hasText(f.getCareTeamId())) {
            sb.append("；分组：未分配健管组");
        } else if (StringUtils.hasText(f.getCareTeamName())) {
            sb.append("；健管组：").append(f.getCareTeamName());
        }
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
        } else if (StringUtils.hasText(f.getServiceStageCode())
                && List.of("FOLLOWUP", "PERIODIC_REPORT", "STAGE_SUMMARY", "ENDED")
                        .contains(f.getServiceStageCode().toUpperCase(Locale.ROOT))) {
            sb.append("；近7日依从：待首次打卡");
        }
        if (f.getWaterTargetMl() != null && f.getWaterTargetMl() > 0) {
            sb.append("；现行方案饮水目标：")
                    .append(f.getWaterTargetMl())
                    .append(" ml（饮食/饮水建议必须与此毫升数一致，禁止另编 1500–1700 等其它区间）");
        }
        if (f.getMedRate7d() != null) {
            sb.append("；近7日服药：").append(Math.round(f.getMedRate7d() * 100)).append("%");
        }
        if (f.getMedMissStreakDays() >= 2) {
            sb.append("（连续 ").append(f.getMedMissStreakDays()).append(" 天未按时完成）");
        }
        if (f.getDietDays7d() != null) {
            sb.append("；近7日饮食记录 ").append(f.getDietDays7d()).append(" 天");
        }
        if (f.getExerciseDays7d() != null) {
            sb.append("；近7日运动记录 ").append(f.getExerciseDays7d()).append(" 天");
        }
        if (StringUtils.hasText(f.getBloodPressure())) {
            sb.append("；最近血压：").append(f.getBloodPressure());
            if (f.isBloodPressureHigh()) {
                sb.append("（偏高）");
            }
            if (f.getBloodPressureAt() != null) {
                sb.append("，测于 ").append(f.getBloodPressureAt().toLocalDate())
                        .append(' ').append(f.getBloodPressureAt().toLocalTime().withSecond(0).withNano(0));
            }
        }
        if (StringUtils.hasText(f.getAllergyText())) {
            sb.append("；过敏史：").append(f.getAllergyText());
        }
        if (StringUtils.hasText(f.getLifestyleSummary())) {
            sb.append("；").append(f.getLifestyleSummary());
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
                    .append("，主责健管组数=")
                    .append(summary.getPrimaryCareTeamCount())
                    .append("，未分组患者=")
                    .append(Math.max(0, summary.getUnassignedPatientCount()))
                    .append("，在管患者=")
                    .append(Math.max(0, summary.getOrgPatientCount()))
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
