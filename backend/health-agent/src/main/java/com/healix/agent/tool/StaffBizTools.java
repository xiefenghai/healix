package com.healix.agent.tool;

import com.healix.agent.gateway.AgentAction;
import com.healix.core.adherence.dto.AdherencePatientItemDto;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import com.healix.core.cockpit.dto.CockpitPriorityCardDto;
import com.healix.core.cockpit.dto.CockpitSummaryDto;
import com.healix.core.cockpit.service.CockpitService;
import com.healix.core.job.support.JobCronSupport;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    private static final int RANK_LIMIT = 5;
    private static final int PLAN_ENDING_DAYS = 7;
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,2})\\s*[:点]\\s*(?:(\\d{1,2})|(半))?");

    private final CockpitService cockpitService;
    private final PatientBizTools patientBizTools;

    public record FocusBundle(CockpitFocusDto focus, String text) {}

    public record OrgBriefBundle(
            CockpitSummaryDto summary, List<CockpitPriorityCardDto> top, String text, List<AgentAction> actions) {}

    /** 机构会话轻量感知：待办数 +「我的患者」首位（不跑完整优先名单格式化）。 */
    /** 批量分组短路：主责组数 + 未分组人数。 */
    public record UnassignedPeek(int primaryTeamCount, int unassignedCount) {}

    public UnassignedPeek peekUnassigned(String tenantId, String orgId) {
        try {
            CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
            if (summary == null) {
                return new UnassignedPeek(0, 0);
            }
            return new UnassignedPeek(
                    Math.max(0, summary.getPrimaryCareTeamCount()),
                    Math.max(0, summary.getUnassignedPatientCount()));
        } catch (Exception e) {
            log.debug("peekUnassigned failed org={}", orgId, e);
            return new UnassignedPeek(0, 0);
        }
    }

    public record MinePeek(            int openTaskCount, int overdueCount, int mineCount, CockpitPriorityCardDto firstMine) {}

    public MinePeek peekMineContext(String tenantId, String orgId) {
        try {
            CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
            List<CockpitPriorityCardDto> mine = cockpitService.myPatients(orgId, null);
            CockpitPriorityCardDto first =
                    mine == null || mine.isEmpty() ? null : mine.get(0);
            int open = summary == null ? 0 : summary.getOpenTaskCount();
            int overdue = summary == null ? 0 : summary.getOverdueCount();
            int mineCount = mine == null ? 0 : mine.size();
            return new MinePeek(open, overdue, mineCount, first);
        } catch (Exception e) {
            log.debug("peekMineContext failed org={}", orgId, e);
            return new MinePeek(0, 0, 0, null);
        }
    }

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

    /** @param message 用户原话；含「完成待办」等时优先给出「去处理某人待办」点人动作 */
    public OrgBriefBundle loadOrgBrief(String tenantId, String orgId, String staffId, String message) {
        try {
            CockpitSummaryDto summary = cockpitService.summary(tenantId, orgId);
            List<CockpitPriorityCardDto> top = cockpitService.topUrgent(tenantId, orgId, 5);
            List<AgentAction> actions = new ArrayList<>();
            boolean wantComplete = wantsCompleteTask(message);
            for (CockpitPriorityCardDto c : top) {
                if (actions.size() >= 4) {
                    break;
                }
                if (!StringUtils.hasText(c.getPeopleId())) {
                    continue;
                }
                if (wantComplete && c.getOpenTaskCount() <= 0) {
                    continue;
                }
                String name = StringUtils.hasText(c.getDisplayName()) ? c.getDisplayName() : "患者";
                String reason = StringUtils.hasText(c.getTopReason()) ? c.getTopReason() : "需关注";
                String label =
                        wantComplete
                                ? "处理「" + name + "」的待办"
                                : name + " · " + reason;
                actions.add(AgentAction.focusPatient(label, c.getPeopleId()));
            }
            // 办结意图但优先名单无人有待办时，仍回落普通点人
            if (wantComplete && actions.isEmpty()) {
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
            }
            String text = formatOrg(summary, top);
            List<AgentAction> insightActions = new ArrayList<>();
            if (wantsAdherenceRank(message)) {
                text += "\n" + adherenceRankText(tenantId, orgId, insightActions);
            }
            if (wantsPlanEnding(message)) {
                text += "\n" + planEndingText(tenantId, orgId, insightActions);
            }
            if (!insightActions.isEmpty()) {
                actions = insightActions;
            }
            return new OrgBriefBundle(summary, top, text, actions);
        } catch (Exception e) {
            log.debug("loadOrgBrief failed org={}", orgId, e);
            return new OrgBriefBundle(null, List.of(), "", List.of());
        }
    }

    private String adherenceRankText(String tenantId, String orgId, List<AgentAction> actions) {
        List<AdherencePatientItemDto> low = cockpitService.lowestAdherence(tenantId, orgId, RANK_LIMIT);
        if (low.isEmpty()) {
            return "近 7 日打卡排名：名下暂无执行中方案的客户。";
        }
        StringBuilder sb = new StringBuilder("近 7 日方案打卡率最低（由低到高）：");
        List<String> nudgeIds = new ArrayList<>();
        for (int i = 0; i < low.size(); i++) {
            AdherencePatientItemDto item = low.get(i);
            String name = nameOf(item);
            sb.append(i == 0 ? "" : "；").append(name).append(' ')
                    .append(Math.round(item.getPlan().getRate7d() * 100)).append('%');
            if (item.getPlan().getStreakDays() > 0) {
                sb.append("，连续 ").append(item.getPlan().getStreakDays()).append(" 天未完成");
            }
            if (item.getLifestyle() != null) {
                sb.append("，近 7 日饮食记录 ").append(item.getLifestyle().getDietDays7d()).append(" 天");
            }
            if (i < 3 && Boolean.TRUE.equals(item.getClientLinked())) {
                nudgeIds.add(item.getPeopleId());
            }
        }
        sb.append("。");
        if (!nudgeIds.isEmpty()) {
            actions.add(AgentAction.nudgeBatch("一起提醒（" + nudgeIds.size() + " 人）", nudgeIds));
        }
        for (int i = 0; i < Math.min(2, low.size()); i++) {
            actions.add(AgentAction.focusPatient("处理" + nameOf(low.get(i)), low.get(i).getPeopleId()));
        }
        actions.add(AgentAction.navigate("打开依从看板", "/workspace/adherence"));
        return sb.toString();
    }

    private String planEndingText(String tenantId, String orgId, List<AgentAction> actions) {
        List<AdherencePatientItemDto> ending = cockpitService.plansEndingSoon(tenantId, orgId, PLAN_ENDING_DAYS);
        if (ending.isEmpty()) {
            return PLAN_ENDING_DAYS + " 天内到期的方案：暂无。";
        }
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        StringBuilder sb = new StringBuilder(PLAN_ENDING_DAYS + " 天内到期的方案共 " + ending.size() + " 位：");
        for (int i = 0; i < ending.size(); i++) {
            AdherencePatientItemDto item = ending.get(i);
            LocalDate end = item.getPlanEndDate();
            long left = ChronoUnit.DAYS.between(today, end);
            sb.append(i == 0 ? "" : "；").append(nameOf(item)).append(' ')
                    .append(end.getMonthValue()).append('月').append(end.getDayOfMonth()).append("日到期")
                    .append(left == 0 ? "（今天）" : "（剩 " + left + " 天）");
        }
        sb.append("。");
        for (int i = 0; i < Math.min(3, ending.size()); i++) {
            actions.add(AgentAction.focusPatient("处理" + nameOf(ending.get(i)), ending.get(i).getPeopleId()));
        }
        AdherencePatientItemDto first = ending.get(0);
        actions.add(AgentAction.triggerCapability(
                "生成" + nameOf(first) + "的复评草稿", "CARE_PLAN", first.getPeopleId()));
        return sb.toString();
    }

    private static String nameOf(AdherencePatientItemDto item) {
        return StringUtils.hasText(item.getDisplayName()) ? item.getDisplayName() : "患者";
    }

    private static AgentAction trendAction(String label, String peopleId) {
        return AgentAction.openSheet(label, "observations", peopleId, Map.of("sub", "trends"));
    }

    public static boolean wantsAdherenceRank(String message) {
        return containsAny(
                message == null ? "" : message.toLowerCase(Locale.ROOT),
                "掉队", "打卡率", "依从", "没打卡", "不打卡", "打卡最", "打卡差", "打卡情况", "谁没完成");
    }

    public static boolean wantsPlanEnding(String message) {
        return containsAny(
                message == null ? "" : message.toLowerCase(Locale.ROOT),
                "到期", "过期", "复评", "续期", "快结束");
    }

    /**
     * 从「今天上午」「明天 15:30」「后天下午3点」这类话术解析计划时间；没有时间词或已过去返回 null。
     */
    public static LocalDateTime parsePlannedAt(String message, LocalDateTime now) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String msg = message.replace('：', ':');
        int dayOffset = -1;
        if (msg.contains("后天")) {
            dayOffset = 2;
        } else if (msg.contains("明天") || msg.contains("明早")) {
            dayOffset = 1;
        } else if (msg.contains("今天") || msg.contains("今早") || msg.contains("今晚")) {
            dayOffset = 0;
        }
        boolean pm = msg.contains("下午") || msg.contains("晚上") || msg.contains("今晚");
        LocalTime time = null;
        Matcher m = CLOCK.matcher(msg);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            int minute = m.group(2) != null ? Integer.parseInt(m.group(2)) : (m.group(3) != null ? 30 : 0);
            if (pm && hour < 12) {
                hour += 12;
            }
            if (hour <= 23 && minute <= 59) {
                time = LocalTime.of(hour, minute);
            }
        }
        if (time == null) {
            if (msg.contains("上午") || msg.contains("早上") || msg.contains("今早") || msg.contains("明早")) {
                time = LocalTime.of(10, 0);
            } else if (msg.contains("中午")) {
                time = LocalTime.of(12, 0);
            } else if (msg.contains("下午")) {
                time = LocalTime.of(15, 0);
            } else if (msg.contains("晚上") || msg.contains("今晚")) {
                time = LocalTime.of(19, 0);
            }
        }
        if (dayOffset < 0 && time == null) {
            return null;
        }
        LocalDate day = now.toLocalDate().plusDays(Math.max(0, dayOffset));
        LocalDateTime at = day.atTime(time != null ? time : LocalTime.of(10, 0));
        return at.isAfter(now) ? at : null;
    }

    private static String plannedLabel(LocalDateTime at, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, at.toLocalDate());
        String day = days == 0 ? "今天" : days == 1 ? "明天" : days == 2 ? "后天" : at.toLocalDate().toString();
        return day + " " + at.toLocalTime().withSecond(0).withNano(0);
    }

    /** 按用户话术与焦点数据给出确定性抽屉动作（不依赖模型编造 path）。 */
    public List<AgentAction> suggestPatientActions(String peopleId, String message, CockpitFocusDto focus) {
        List<AgentAction> out = new ArrayList<>();
        if (!StringUtils.hasText(peopleId)) {
            return out;
        }
        String msg = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean wantComplete = wantsCompleteTask(message);
        boolean wantFollowup = containsAny(msg, "随访", "回访", "起草随访", "写随访", "创建随访");
        boolean wantChat = containsAny(msg, "联系", "沟通", "回复患者", "回患者", "发消息", "起草沟通", "写回复");
        boolean wantObs = containsAny(msg, "指标", "血压", "血糖", "健康数据", "录入", "化验", "检验");
        boolean wantPlan = containsAny(msg, "方案", "打卡", "依从", "执行计划");
        boolean wantArchive = containsAny(msg, "档案", "完整度", "补档", "缺项");
        boolean wantAssess = containsAny(msg, "评估", "分标", "风险", "cdss", "糖标", "压标");
        boolean wantMed = containsAny(msg, "用药", "服药", "吃药", "药品", "漏服", "停药", "medication");
        boolean wantReport = containsAny(msg, "报告", "月报", "周报", "季报");
        // 建档追问/开场：绝不能挂分组按钮（焦点患者未入组时曾误挂「去分组」「去创建健管组」）
        boolean wantCreateArchive = containsAny(
                msg,
                "建档",
                "新建患者",
                "新建客户",
                "新建档案",
                "录入患者",
                "登记患者",
                "创建患者",
                "建一个患者",
                "建个患者",
                "create archive");
        boolean needTeam = needsCareTeamAssign(focus);
        boolean wantTeam =
                containsAny(msg, "分组", "分配健管组", "分配组", "入组", "改挂", "健管组", "team assign");
        boolean competingIntent = wantComplete
                || wantFollowup
                || wantChat
                || wantObs
                || wantPlan
                || wantArchive
                || wantAssess
                || wantMed
                || wantReport
                || wantCreateArchive;

        // 仅明确说分组，或未入组且本轮没有其它业务意图时，才置顶分组入口
        if (wantTeam || (needTeam && !competingIntent)) {
            out.add(AgentAction.navigate("去分组", "join-care-team", peopleId));
            if (needTeam) {
                out.add(AgentAction.createCareTeam("去创建健管组", null));
            }
        }

        // 办结意图：挂齐当前患者可办结入口（人点才执行；同抽屉类合并，上限与前端芯片一致）
        if (wantComplete && focus != null && focus.getOpenTasks() != null) {
            appendGroupedTaskCloseActions(out, peopleId, focus.getOpenTasks(), 8);
        }

        if (wantFollowup) {
            LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
            LocalDateTime plannedAt = parsePlannedAt(message, now);
            String label = plannedAt == null
                    ? "一键创建随访待办"
                    : "创建随访（" + plannedLabel(plannedAt, now.toLocalDate()) + "）";
            out.add(AgentAction.createFollowupTask(label, peopleId, "ROUTINE", null, plannedAt));
            out.add(AgentAction.openSheet("打开随访页", "followups", peopleId));
        }
        if (wantChat) {
            if (focus != null && Boolean.TRUE.equals(focus.getClientLinked())) {
                out.add(AgentAction.nudgePatient("站内提醒患者", peopleId));
            }
            out.add(AgentAction.openSheet("联系患者", "care-chat", peopleId));
        }
        if (wantObs) {
            if (containsAny(msg, "血压")) {
                out.add(trendAction("看血压趋势", peopleId));
            } else if (containsAny(msg, "血糖")) {
                out.add(trendAction("看血糖趋势", peopleId));
            } else if (containsAny(msg, "趋势", "指标")) {
                out.add(trendAction("看指标趋势", peopleId));
            }
            out.add(AgentAction.openSheet("录入健康数据", "observations", peopleId));
        }
        if (wantPlan) {
            out.add(AgentAction.triggerCapability("一键生成方案草稿", "CARE_PLAN", peopleId));
            out.add(AgentAction.openSheet("查看方案", "care-plan", peopleId));
            if (focus != null && Boolean.TRUE.equals(focus.getClientLinked())) {
                Double rate = focus.getPlanRate7d();
                if (rate != null && rate < 0.7) {
                    out.add(AgentAction.nudgePatient("提醒补打卡", peopleId));
                }
            }
        }
        if (wantMed) {
            out.add(AgentAction.openSheet("查看用药", "medications", peopleId));
            if (focus != null && Boolean.TRUE.equals(focus.getClientLinked()) && focus.isMedIncomplete()) {
                out.add(AgentAction.nudgePatient("提醒按时服药", peopleId));
            }
        }
        if (wantAssess) {
            out.add(AgentAction.openSheet("查看评估", "assessments", peopleId));
        }
        if (wantArchive) {
            out.add(AgentAction.openSheet("查看档案", "archive", peopleId));
        }
        if (wantReport) {
            out.add(AgentAction.triggerCapability("一键生成报告点评", "REPORT_SUMMARY", peopleId));
            if (wantsReportSummary(message)) {
                out.add(AgentAction.openSheet("打开报告审阅", "reports", peopleId));
            } else {
                out.add(AgentAction.openSheet("查看报告", "reports", peopleId));
            }
        }

        // 无明确意图时，按焦点缺口补默认动作（有待办则给办结入口）
        if (out.isEmpty() && focus != null) {
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
                    out.add(AgentAction.triggerCapability("一键生成方案草稿", "CARE_PLAN", peopleId));
                    out.add(AgentAction.openSheet("处理方案待办", "care-plan", peopleId));
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

        // 办结意图最多挂 8 条；其它建议保持精简
        return limit(out, wantComplete ? 8 : 5);
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
     * 办结入口批量挂载：同抽屉类（审阅报告 / 处理方案 / 评估）合并为 1 个芯片并标注数量；
     * 表单类 COMPLETE_TASK 仍按任务各挂一条。
     */
    private static void appendGroupedTaskCloseActions(
            List<AgentAction> out,
            String peopleId,
            List<CockpitFocusDto.OpenTaskBrief> tasks,
            int max) {
        if (tasks == null || tasks.isEmpty() || max <= 0) {
            return;
        }
        Map<String, Integer> sheetCounts = new LinkedHashMap<>();
        for (CockpitFocusDto.OpenTaskBrief t : tasks) {
            String mode = mergeableSheetMode(t == null ? null : t.getTaskType());
            if (mode != null) {
                sheetCounts.merge(mode, 1, Integer::sum);
            }
        }
        Set<String> emittedSheets = new HashSet<>();
        int n = 0;
        for (CockpitFocusDto.OpenTaskBrief t : tasks) {
            if (n >= max) {
                break;
            }
            if (t == null || !StringUtils.hasText(t.getId())) {
                continue;
            }
            String type = t.getTaskType() == null ? "" : t.getTaskType();
            String mode = mergeableSheetMode(type);
            if (mode != null) {
                if (emittedSheets.contains(mode)) {
                    continue;
                }
                emittedSheets.add(mode);
                int count = sheetCounts.getOrDefault(mode, 1);
                out.add(mergedSheetAction(mode, peopleId, t, count));
                n++;
                continue;
            }
            int before = out.size();
            if (appendTaskCloseActions(out, peopleId, t) && out.size() > before) {
                n++;
            }
        }
    }

    /** 多条任务点开后落同一抽屉、且无单条专属 payload 的类型 → 合并芯片。 */
    private static String mergeableSheetMode(String taskType) {
        if ("REPORT_REVIEW".equals(taskType)) {
            return "reports";
        }
        if ("PLAN_CREATE".equals(taskType) || "PLAN_REVIEW".equals(taskType)) {
            return "care-plan";
        }
        return null;
    }

    private static AgentAction mergedSheetAction(
            String mode, String peopleId, CockpitFocusDto.OpenTaskBrief sample, int count) {
        String countMark = count > 1 ? "（" + count + "）" : "";
        if ("reports".equals(mode)) {
            return AgentAction.openSheet("审阅管理报告" + countMark, mode, peopleId);
        }
        if ("care-plan".equals(mode)) {
            return AgentAction.openSheet("处理方案待办" + countMark, mode, peopleId);
        }
        String typeLabel = StringUtils.hasText(sample.getTaskTypeLabel())
                ? sample.getTaskTypeLabel()
                : (sample.getTaskType() == null ? "" : sample.getTaskType());
        return AgentAction.openSheet(typeLabel + countMark, mode, peopleId);
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

    public static boolean wantsCompleteTask(String message) {
        return containsAny(
                message == null ? "" : message.toLowerCase(Locale.ROOT),
                "完成待办",
                "办结待办",
                "办结任务",
                "完成任务",
                "处理待办",
                "清待办",
                "关单",
                "搞定待办",
                "把待办做完",
                "待办办掉");
    }

    /**
     * 机构会话是否应在回复下挂优先名单「人名」芯片。
     * 创建健管组、婉拒、纯闲聊等与选人无关的问题返回 false。
     */
    public static boolean wantsPriorityPatientChips(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        if (wantsCompleteTask(message) || wantsAdherenceRank(message) || wantsPlanEnding(message)) {
            return true;
        }
        String n = message.trim().replaceAll("\\s+", "");
        return n.contains("优先")
                || n.contains("待办")
                || n.contains("跟进谁")
                || n.contains("先忙")
                || n.contains("先处理谁")
                || n.contains("安排今天")
                || n.contains("今天安排")
                || n.contains("有谁要")
                || n.contains("哪些人")
                || n.contains("名单")
                || n.contains("紧急")
                || n.contains("无事可做")
                || n.contains("从哪开始")
                || n.equals("下一步")
                || n.matches(".*(处理|跟进|打开|看).{0,8}(患者|客户|谁).*")
                || n.matches(".*(我)?(现在|今天|接下来)?.{0,4}(要|该|应该)?(干|做)(什么|啥).*");
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

    public static boolean wantsReportSummary(String message) {
        return containsAny(
                message == null ? "" : message.toLowerCase(Locale.ROOT),
                "报告点评",
                "点评报告",
                "生成点评",
                "ai点评",
                "写点评",
                "写寄语",
                "审阅报告",
                "管理报告点评",
                "报告寄语",
                "生成周报",
                "生成月报",
                "生成报告",
                "出周报",
                "出报告");
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
