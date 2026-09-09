package com.healix.agent.tool;

import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanTodayTaskDto;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.followup.catalog.FollowupRecordStatus;
import com.healix.core.followup.dto.FollowupListItemDto;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.medication.dto.MedicationIntakeViewDto;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.enums.MedicationStatusEnum;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.report.dto.HealthReportListItemDto;
import com.healix.core.report.service.HealthReportService;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.service.VitalService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * C 端助手的业务只读工具：把患者自己的方案/用药/指标/随访/报告读成一段短文本，
 * 交给 LLM 做「有据可依」的回复，同时给出可点的跳转动作。
 *
 * <p>所有方法都只读，且走 C 端同款的 forPatient 入口（内部校验 people 属于该租户），
 * 不额外放宽权限。任一子查询失败只降级为空块，不影响整轮对话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatientBizTools {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final CarePlanCheckinService carePlanCheckinService;
    private final MedicationService medicationService;
    private final VitalService vitalService;
    private final FollowupService followupService;
    private final HealthReportService healthReportService;

    /**
     * 工具输出：喂给 LLM 的文本 + 建议给用户的跳转动作。
     *
     * @param text    形如「今日方案：3/5 已打卡；未打卡：早餐后测血糖、快走30分钟」
     * @param actions 去重后的跳转动作
     */
    public record ToolOutput(String text, List<AgentQuickAction> actions) {

        public static ToolOutput empty() {
            return new ToolOutput("", List.of());
        }

        public boolean isEmpty() {
            return !StringUtils.hasText(text) && actions.isEmpty();
        }
    }

    /** 今日方案打卡进度与未打卡任务。 */
    public ToolOutput todayPlan(String tenantId, String peopleId) {
        try {
            CarePlanTodayDto today = carePlanCheckinService.getPatientToday(tenantId, peopleId, LocalDate.now());
            if (today == null || today.getPlanId() == null) {
                return new ToolOutput(
                        "今日方案：暂无生效的健康管理方案",
                        List.of(new AgentQuickAction("查看方案", "/care-plan")));
            }
            List<String> pending = new ArrayList<>();
            for (CarePlanTodayTaskDto task : nullSafe(today.getTasks())) {
                if (!"DONE".equals(task.getCheckinStatus()) && !"SKIPPED".equals(task.getCheckinStatus())) {
                    pending.add(task.getTitle());
                }
            }
            StringBuilder sb = new StringBuilder("今日方案：");
            sb.append(today.getPlanTitle() == null ? "健康管理方案" : today.getPlanTitle())
                    .append("，已打卡 ")
                    .append(today.getDoneTasks())
                    .append("/")
                    .append(today.getTotalTasks())
                    .append(" 项");
            if (!pending.isEmpty()) {
                sb.append("；未打卡：").append(String.join("、", limit(pending, 5)));
            }
            if (StringUtils.hasText(today.getGoalSummary())) {
                sb.append("；方案目标：").append(today.getGoalSummary());
            }
            List<AgentQuickAction> actions = pending.isEmpty()
                    ? List.of(new AgentQuickAction("查看方案", "/care-plan"))
                    : List.of(new AgentQuickAction("去打卡", "/care-plan"));
            return new ToolOutput(sb.toString(), actions);
        } catch (Exception e) {
            log.debug("todayPlan tool failed people={}", peopleId, e);
            return ToolOutput.empty();
        }
    }

    /** 在用药清单与今日服药次数（按频次展开）。 */
    public ToolOutput medicationToday(String tenantId, String peopleId) {
        try {
            List<MedicationViewDto> meds =
                    medicationService.listForPatient(tenantId, peopleId, MedicationStatusEnum.ACTIVE.name());
            if (meds.isEmpty()) {
                return new ToolOutput(
                        "用药：当前没有在用药品",
                        List.of(new AgentQuickAction("管理用药", "/medications")));
            }
            Map<String, Set<String>> takenSlots = new LinkedHashMap<>();
            for (MedicationIntakeViewDto intake :
                    medicationService.listIntakesByDateForPatient(tenantId, peopleId, LocalDate.now())) {
                if (MedicationIntakeStatusEnum.TAKEN.name().equals(intake.getStatus())) {
                    takenSlots
                            .computeIfAbsent(intake.getMedicationId(), k -> new LinkedHashSet<>())
                            .add(intake.getTimeSlot() == null ? "OTHER" : intake.getTimeSlot());
                }
            }
            int dueDoses = 0;
            int takenDoses = 0;
            List<String> pending = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (MedicationViewDto med : meds) {
                names.add(med.getDrugName());
                if (med.isPrn() || med.getDueDoseCount() <= 0) {
                    continue;
                }
                int taken = Math.min(
                        takenSlots.getOrDefault(med.getId(), Set.of()).size(), med.getDueDoseCount());
                dueDoses += med.getDueDoseCount();
                takenDoses += taken;
                if (taken < med.getDueDoseCount()) {
                    pending.add(med.getDrugName() + "（" + taken + "/" + med.getDueDoseCount() + " 次）");
                }
            }
            StringBuilder sb = new StringBuilder("用药：在用 ")
                    .append(meds.size())
                    .append(" 种（")
                    .append(String.join("、", limit(names, 6)))
                    .append("）");
            if (dueDoses > 0) {
                sb.append("；今日已服 ").append(takenDoses).append("/").append(dueDoses).append(" 次");
            }
            if (!pending.isEmpty()) {
                sb.append("；待服：").append(String.join("、", limit(pending, 5)));
            }
            List<AgentQuickAction> actions = pending.isEmpty()
                    ? List.of(new AgentQuickAction("管理用药", "/medications"))
                    : List.of(new AgentQuickAction("去服药打卡", "/medications"));
            return new ToolOutput(sb.toString(), actions);
        } catch (Exception e) {
            log.debug("medicationToday tool failed people={}", peopleId, e);
            return ToolOutput.empty();
        }
    }

    /** 最近 7 天的关键指标（血压/血糖/体重/心率/步数）。 */
    public ToolOutput recentMetrics(String tenantId, String peopleId, int days) {
        try {
            int window = Math.min(Math.max(days, 1), 90);
            LocalDateTime to = LocalDateTime.now();
            LocalDateTime from = to.minusDays(window);
            List<String> parts = new ArrayList<>();
            appendLatest(parts, tenantId, peopleId, from, to, MetricTypeEnum.BLOOD_PRESSURE_SYS, "收缩压");
            appendLatest(parts, tenantId, peopleId, from, to, MetricTypeEnum.BLOOD_PRESSURE_DIA, "舒张压");
            appendLatest(parts, tenantId, peopleId, from, to, MetricTypeEnum.BLOOD_GLUCOSE, "血糖");
            appendLatest(parts, tenantId, peopleId, from, to, MetricTypeEnum.WEIGHT, "体重");
            appendLatest(parts, tenantId, peopleId, from, to, MetricTypeEnum.HEART_RATE, "心率");
            appendLatest(parts, tenantId, peopleId, from, to, MetricTypeEnum.STEPS, "步数");
            vitalService
                    .averageGlucoseLastDays(tenantId, peopleId, window)
                    .ifPresent(avg -> parts.add(String.format("近%d天血糖均值 %.2f", window, avg)));
            if (parts.isEmpty()) {
                return new ToolOutput(
                        "指标：近" + window + "天没有记录",
                        List.of(new AgentQuickAction("记一次指标", "/health/record")));
            }
            return new ToolOutput(
                    "指标（近" + window + "天最新值）：" + String.join("；", parts),
                    List.of(
                            new AgentQuickAction("记一次指标", "/health/record"),
                            new AgentQuickAction("看健康数据", "/health")));
        } catch (Exception e) {
            log.debug("recentMetrics tool failed people={}", peopleId, e);
            return ToolOutput.empty();
        }
    }

    /** 待办随访与最近一次随访结论。 */
    public ToolOutput followups(String tenantId, String peopleId) {
        try {
            List<FollowupListItemDto> items = followupService.listForPatient(tenantId, peopleId, 20);
            List<String> open = new ArrayList<>();
            String lastDone = null;
            for (FollowupListItemDto item : items) {
                if (FollowupRecordStatus.OPEN.name().equals(item.getStatus())) {
                    String when = item.getPlannedAt() != null
                            ? item.getPlannedAt().format(DAY)
                            : (item.getDueAt() != null ? item.getDueAt().format(DAY) : "待安排");
                    open.add(when + " " + defaultText(item.getTitle(), item.getRecordTypeLabel(), "随访"));
                } else if (lastDone == null && FollowupRecordStatus.DONE.name().equals(item.getStatus())) {
                    String when = item.getCompletedAt() != null ? item.getCompletedAt().format(DAY) : "近期";
                    lastDone = when + " " + defaultText(item.getSummary(), item.getTitle(), "已完成随访");
                }
            }
            StringBuilder sb = new StringBuilder("随访：");
            if (open.isEmpty()) {
                sb.append("暂无待办随访");
            } else {
                sb.append("待办 ").append(open.size()).append(" 条（").append(String.join("；", limit(open, 3)))
                        .append("）");
            }
            if (lastDone != null) {
                sb.append("；上次随访：").append(trim(lastDone, 80));
            }
            List<AgentQuickAction> actions = new ArrayList<>();
            actions.add(new AgentQuickAction("查看随访", "/followups"));
            if (open.isEmpty()) {
                actions.add(new AgentQuickAction("申请回访", "/followups?request=1"));
            }
            return new ToolOutput(sb.toString(), actions);
        } catch (Exception e) {
            log.debug("followups tool failed people={}", peopleId, e);
            return ToolOutput.empty();
        }
    }

    /** 最近已发布的管理报告。 */
    public ToolOutput latestReport(String tenantId, String peopleId) {
        try {
            List<HealthReportListItemDto> reports =
                    healthReportService.listPublishedForPatient(tenantId, peopleId, 3);
            if (reports.isEmpty()) {
                return new ToolOutput("管理报告：暂无已发布报告", List.of());
            }
            HealthReportListItemDto latest = reports.get(0);
            StringBuilder sb = new StringBuilder("管理报告：最新一份「")
                    .append(defaultText(latest.getTitle(), latest.getPeriodTypeLabel(), "管理报告"))
                    .append("」");
            if (latest.getPeriodStart() != null && latest.getPeriodEnd() != null) {
                sb.append("（").append(latest.getPeriodStart().format(DAY)).append("~")
                        .append(latest.getPeriodEnd().format(DAY)).append("）");
            }
            if (StringUtils.hasText(latest.getStaffComment())) {
                sb.append("，健管师点评：").append(trim(latest.getStaffComment(), 120));
            }
            return new ToolOutput(
                    sb.toString(),
                    List.of(new AgentQuickAction("看报告", "/management-reports/" + latest.getId())));
        } catch (Exception e) {
            log.debug("latestReport tool failed people={}", peopleId, e);
            return ToolOutput.empty();
        }
    }

    private void appendLatest(
            List<String> parts,
            String tenantId,
            String peopleId,
            LocalDateTime from,
            LocalDateTime to,
            MetricTypeEnum metric,
            String label) {
        List<VitalRecord> rows = vitalService.list(tenantId, peopleId, metric.name(), from, to);
        if (rows.isEmpty()) {
            return;
        }
        VitalRecord latest = rows.stream()
                .filter(r -> r.getRecordedAt() != null)
                .max((a, b) -> a.getRecordedAt().compareTo(b.getRecordedAt()))
                .orElse(rows.get(0));
        StringBuilder sb = new StringBuilder(label).append(" ").append(latest.getValue());
        if (StringUtils.hasText(latest.getUnit())) {
            sb.append(latest.getUnit());
        }
        if (latest.getRecordedAt() != null) {
            sb.append("（").append(latest.getRecordedAt().format(DAY_TIME)).append("）");
        }
        parts.add(sb.toString());
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> limit(List<String> list, int max) {
        if (list.size() <= max) {
            return list;
        }
        List<String> out = new ArrayList<>(list.subList(0, max));
        out.add("等" + list.size() + "项");
        return out;
    }

    private static String defaultText(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        String plain = text.replaceAll("\\s+", " ").trim();
        return plain.length() <= max ? plain : plain.substring(0, max) + "…";
    }
}
