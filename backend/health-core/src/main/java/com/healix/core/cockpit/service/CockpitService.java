package com.healix.core.cockpit.service;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.exception.BusinessException;
import com.healix.core.adherence.dto.AdherencePatientDetailDto;
import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.archive.dto.ArchiveCompletenessDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveCompletenessService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.service.AssessmentOrchestrator;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.cockpit.dto.CockpitFocusDto;
import com.healix.core.cockpit.dto.CockpitPriorityCardDto;
import com.healix.core.cockpit.dto.CockpitSummaryDto;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.observation.dto.MetricLatestSlotDto;
import com.healix.core.observation.service.MetricService;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.people.support.DiseaseCodeLabels;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.service.CarePlanService;
import com.healix.core.report.catalog.HealthReportStatus;
import com.healix.core.report.dto.HealthReportListItemDto;
import com.healix.core.report.service.HealthReportService;
import com.healix.core.worktask.catalog.WorkspaceTaskPriority;
import com.healix.core.worktask.catalog.WorkspaceTaskStatus;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.dto.WorkspaceTaskQuery;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 智能驾驶舱感知层：汇总摘要、按人聚合今日优先、焦点患者快照。
 *
 * <p>左栏优先名单只看当前员工名下（assignee）的 OPEN 任务，再按超期 / 优先级 / 待办数排序。
 */
@Service
@RequiredArgsConstructor
public class CockpitService {

    private static final int TASK_FETCH = 100;
    private static final String CONTROL_ENGINE = "DIABETES_CONTROL_LABEL";
    private static final List<String[]> RISK_ENGINES = List.of(
            new String[] {"CDRS", "糖尿病"},
            new String[] {"HYPERTENSION_RISK", "高血压"},
            new String[] {"OBESITY_SCREEN", "肥胖"});

    private final OrgWorkspaceService orgWorkspaceService;
    private final AdherenceQueryService adherenceQueryService;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final CareTeamMapper careTeamMapper;
    private final ArchiveAccessService archiveAccessService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final ArchiveCompletenessService archiveCompletenessService;
    private final AssessmentOrchestrator assessmentOrchestrator;
    private final MetricService metricService;
    private final CarePlanService carePlanService;
    private final HealthReportService healthReportService;

    public CockpitSummaryDto summary(String tenantId, String orgId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();

        CockpitSummaryDto dto = new CockpitSummaryDto();
        // 顶栏「待办」：只计挂在我身上的 OPEN 任务条数
        long mineOpen = workspaceTaskMapper.countOpenMine(orgId, staffId);
        dto.setOpenTaskCount((int) mineOpen);
        dto.setRedCount(0);

        List<CockpitPriorityCardDto> all = buildPriorityCards(tenantId, orgId, staffId);
        int overdue = 0;
        int urgent = 0;
        int watch = 0;
        int mine = 0;
        for (CockpitPriorityCardDto card : all) {
            overdue += Math.max(0, card.getOverdueTaskCount());
            if (matchTab("urgent", card)) {
                urgent++;
            }
            if (matchTab("watch", card)) {
                watch++;
            }
            if (matchTab("mine", card)) {
                mine++;
            }
        }
        dto.setOverdueCount(overdue);
        dto.setUrgentCount(urgent);
        dto.setWatchCount(watch);
        dto.setMineCount(mine);
        return dto;
    }

    /**
     * 左栏优先患者：仅当前员工 assignee 的 OPEN 任务，按人聚合后排序并按 tab 过滤。
     *
     * @param tab urgent|mine = 我的全部在办；watch = 非高紧迫子集；默认 urgent
     */
    public List<CockpitPriorityCardDto> priority(String tenantId, String orgId, String tab) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        String bucket = normalizeTab(tab);
        return buildPriorityCards(tenantId, orgId, staffId).stream()
                .filter(c -> matchTab(bucket, c))
                .toList();
    }

    /**
     * 我的患者：当前员工作为主责健管师的健管组下全部患者（可按姓名关键字筛选）。
     * 不要求有待办，供驾驶舱注入对话使用。
     */
    public List<OrgPatientListItem> myPatients(String orgId, String keyword) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        List<CareTeam> teams = careTeamMapper.listByPrimaryCareManager(orgId, staffId);
        if (teams.isEmpty()) {
            return List.of();
        }
        Map<String, OrgPatientListItem> byPeople = new java.util.LinkedHashMap<>();
        for (CareTeam team : teams) {
            List<OrgPatientListItem> rows =
                    orgWorkspaceService.listOrgPatients(orgId, keyword, team.getId(), null);
            for (OrgPatientListItem row : rows) {
                if (row == null || !StringUtils.hasText(row.getPeopleId())) {
                    continue;
                }
                byPeople.putIfAbsent(row.getPeopleId(), row);
            }
        }
        List<OrgPatientListItem> out = new ArrayList<>(byPeople.values());
        out.sort(Comparator.comparing(
                r -> r.getDisplayName() == null ? "" : r.getDisplayName(),
                String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** 构建全量优先卡（未按 tab 过滤），供 summary / priority / topUrgent 共用。 */
    private List<CockpitPriorityCardDto> buildPriorityCards(String tenantId, String orgId, String staffId) {
        Map<String, CockpitPriorityCardDto> byPeople = new HashMap<>();
        for (WorkspaceTask task : loadMineOpenTasks(tenantId, orgId, staffId)) {
            CockpitPriorityCardDto card = ensureCard(byPeople, task.getPeopleId(), null);
            if (!StringUtils.hasText(card.getDisplayName())) {
                PeopleProfile p = peopleProfileMapper.findById(task.getPeopleId());
                card.setDisplayName(p == null ? task.getPeopleId() : p.getDisplayName());
            }
            fillProfile(card);
            fillTeamIfMissing(card, orgId, task.getPeopleId());
            card.getTaskIds().add(task.getId());
            WorkspaceTaskType type = WorkspaceTaskType.require(task.getTaskType());
            addBadge(card, type.name());
            addTaskType(card, type);
            touchLatestTaskAt(card, task.getOpenedAt());
            bumpMaxPriority(card, effectivePriority(task, type));
            addBadge(card, "MINE");
            if (isOverdue(task)) {
                addBadge(card, "OVERDUE");
                card.setOverdueTaskCount(card.getOverdueTaskCount() + 1);
            }
            if ("PATIENT_REQUEST".equals(task.getSource())) {
                addBadge(card, "PATIENT_REQUEST");
            }
        }

        List<CockpitPriorityCardDto> all = new ArrayList<>();
        for (CockpitPriorityCardDto card : byPeople.values()) {
            fillProfile(card);
            int open = card.getTaskIds() == null ? 0 : card.getTaskIds().size();
            if (open <= 0) {
                continue;
            }
            card.setOpenTaskCount(open);
            if (!StringUtils.hasText(card.getMaxTaskPriority())) {
                card.setMaxTaskPriority(WorkspaceTaskPriority.LOW.name());
            }
            card.setUrgencyScore(packUrgencyScore(card));
            card.setTopReason(topReason(card));
            card.setBucket(classifyBucket(card));
            all.add(card);
        }
        // 三维排序：超期数 → 最高任务优先级 → OPEN 待办数
        all.sort(Comparator.comparingInt(CockpitPriorityCardDto::getUrgencyScore)
                .reversed()
                .thenComparing(c -> c.getDisplayName() == null ? "" : c.getDisplayName()));
        return all;
    }

    public CockpitFocusDto focus(String tenantId, String orgId, String peopleId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        var patient = orgWorkspaceService.getOrgPatient(orgId, peopleId);
        AdherencePatientDetailDto detail = adherenceQueryService.patientDetail(tenantId, orgId, peopleId, null, 7);

        CockpitFocusDto dto = new CockpitFocusDto();
        dto.setPeopleId(peopleId);
        dto.setDisplayName(patient.getDisplayName());
        dto.setGender(patient.getGender());
        if (patient.getBirthday() != null) {
            dto.setBirthday(patient.getBirthday().toString());
        }
        dto.setCareTeamId(patient.getCareTeamId());
        dto.setCareTeamName(patient.getCareTeamName());
        dto.setClientLinked(patient.getClientLinked());
        dto.setRiskLevel(detail.getRiskLevel());
        if (detail.getPlan() != null) {
            dto.setStreakDays(detail.getPlan().getStreakDays());
            dto.setPlanRate7d(detail.getPlan().getRate7d());
            dto.setPlanIncomplete(detail.getPlan().isTodayIncomplete());
        }
        if (detail.getMed() != null) {
            dto.setMedIncomplete(detail.getMed().isTodayIncomplete());
            dto.setMedDueDoseCount(detail.getMed().getDueDoseCount());
            dto.setMedTakenDoseCount(detail.getMed().getTakenDoseCount());
        }
        dto.setArchivePath("/workspace/patients/" + peopleId + "/archive");
        dto.setCareChatPath("/workspace/care-chat/" + peopleId);
        fillDiseases(dto, tenantId, peopleId);
        dto.setBloodPressure(resolveBloodPressure(tenantId, orgId, peopleId));
        fillArchiveCompleteness(dto, tenantId, peopleId);
        fillAssessmentTags(dto, tenantId, orgId, peopleId);

        String staffId = requireStaffId();
        List<String> exclude = doctorPublicExclude();
        for (WorkspaceTask task : loadVisibleOpenTasks(tenantId, orgId, staffId, exclude)) {
            if (!peopleId.equals(task.getPeopleId())) {
                continue;
            }
            CockpitFocusDto.OpenTaskBrief brief = new CockpitFocusDto.OpenTaskBrief();
            brief.setId(task.getId());
            WorkspaceTaskType type = WorkspaceTaskType.require(task.getTaskType());
            brief.setTaskType(type.name());
            brief.setTaskTypeLabel(type.label());
            brief.setSummary(task.getSummary());
            brief.setPriority(task.getPriority());
            brief.setOverdue(isOverdue(task));
            dto.getOpenTasks().add(brief);
        }
        fillPendingDrafts(dto, tenantId, orgId, peopleId);
        return dto;
    }

    private void fillPendingDrafts(CockpitFocusDto dto, String tenantId, String orgId, String peopleId) {
        try {
            CarePlanBundleDto bundle = carePlanService.getBundle(tenantId, orgId, peopleId);
            if (bundle != null && bundle.getDraft() != null) {
                CockpitFocusDto.PendingDraft d = new CockpitFocusDto.PendingDraft();
                d.setKind("CARE_PLAN");
                d.setId(bundle.getDraft().getId());
                String title = bundle.getPlan() != null ? bundle.getPlan().getTitle() : null;
                d.setTitle(StringUtils.hasText(title) ? title : "管理方案草稿");
                String goal = bundle.getPlan() != null ? bundle.getPlan().getGoalSummary() : null;
                String source = bundle.getDraft().getSource();
                d.setSummary(
                        (StringUtils.hasText(goal) ? goal : "待审阅发布")
                                + (StringUtils.hasText(source) ? " · " + source : ""));
                d.setSheetMode("care-plan");
                dto.getPendingDrafts().add(d);
            }
        } catch (Exception ignored) {
            // 焦点快照不因方案失败而中断
        }
        try {
            for (HealthReportListItemDto r : healthReportService.listByPeople(orgId, peopleId, 10)) {
                if (r == null || !HealthReportStatus.DRAFT.matches(r.getStatus())) {
                    continue;
                }
                CockpitFocusDto.PendingDraft d = new CockpitFocusDto.PendingDraft();
                d.setKind("REPORT");
                d.setId(r.getId());
                d.setTitle(StringUtils.hasText(r.getTitle()) ? r.getTitle() : "管理报告草稿");
                String comment = r.getStaffComment();
                d.setSummary(
                        StringUtils.hasText(comment)
                                ? "已有点评草稿，待发布"
                                : (StringUtils.hasText(r.getPeriodTypeLabel())
                                        ? r.getPeriodTypeLabel() + " · 待审阅"
                                        : "待审阅发布"));
                d.setSheetMode("reports");
                dto.getPendingDrafts().add(d);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 供简报生成：urgent 前 N 人摘要。 */
    public List<CockpitPriorityCardDto> topUrgent(String tenantId, String orgId, int limit) {
        return priority(tenantId, orgId, "urgent").stream().limit(Math.max(1, limit)).toList();
    }

    /** 当前员工名下 OPEN 任务（assignee = me）。 */
    private List<WorkspaceTask> loadMineOpenTasks(String tenantId, String orgId, String staffId) {
        WorkspaceTaskQuery mine = baseOpenQuery(tenantId, orgId);
        mine.setPool("MINE");
        mine.setAssigneeStaffId(staffId);
        mine.setPageSize(TASK_FETCH);
        return workspaceTaskMapper.listByQuery(mine);
    }

    /** 焦点面板：公共池 + 我的池中该患者的 OPEN（便于看到机构侧全部待办）。 */
    private List<WorkspaceTask> loadVisibleOpenTasks(
            String tenantId, String orgId, String staffId, List<String> exclude) {
        List<WorkspaceTask> out = new ArrayList<>();
        WorkspaceTaskQuery pub = baseOpenQuery(tenantId, orgId);
        pub.setPool("PUBLIC");
        pub.setExcludeTaskTypes(exclude.isEmpty() ? null : exclude);
        pub.setPageSize(TASK_FETCH);
        out.addAll(workspaceTaskMapper.listByQuery(pub));

        WorkspaceTaskQuery mine = baseOpenQuery(tenantId, orgId);
        mine.setPool("MINE");
        mine.setAssigneeStaffId(staffId);
        mine.setPageSize(TASK_FETCH);
        out.addAll(workspaceTaskMapper.listByQuery(mine));
        return out;
    }

    private static WorkspaceTaskQuery baseOpenQuery(String tenantId, String orgId) {
        WorkspaceTaskQuery q = new WorkspaceTaskQuery();
        q.setTenantId(tenantId);
        q.setOrgId(orgId);
        q.setStatus(WorkspaceTaskStatus.OPEN.name());
        q.setOffset(0);
        return q;
    }

    private CockpitPriorityCardDto ensureCard(
            Map<String, CockpitPriorityCardDto> byPeople, String peopleId, String displayName) {
        return byPeople.computeIfAbsent(peopleId, id -> {
            CockpitPriorityCardDto card = new CockpitPriorityCardDto();
            card.setPeopleId(id);
            card.setDisplayName(displayName);
            card.setBadges(new ArrayList<>());
            card.setTaskIds(new ArrayList<>());
            card.setTaskTypes(new ArrayList<>());
            card.setTaskTypeLabels(new ArrayList<>());
            return card;
        });
    }

    private void fillProfile(CockpitPriorityCardDto card) {
        if (StringUtils.hasText(card.getGender()) && StringUtils.hasText(card.getDisplayName())) {
            return;
        }
        PeopleProfile p = peopleProfileMapper.findById(card.getPeopleId());
        if (p == null) {
            return;
        }
        if (!StringUtils.hasText(card.getDisplayName())) {
            card.setDisplayName(p.getDisplayName());
        }
        if (!StringUtils.hasText(card.getGender())) {
            card.setGender(p.getGender());
        }
    }

    private static void addTaskType(CockpitPriorityCardDto card, WorkspaceTaskType type) {
        Set<String> codes = new LinkedHashSet<>(card.getTaskTypes());
        Set<String> labels = new LinkedHashSet<>(card.getTaskTypeLabels());
        codes.add(type.name());
        labels.add(type.label());
        card.setTaskTypes(new ArrayList<>(codes));
        card.setTaskTypeLabels(new ArrayList<>(labels));
    }

    private static void touchLatestTaskAt(CockpitPriorityCardDto card, LocalDateTime openedAt) {
        if (openedAt == null) {
            return;
        }
        if (card.getLatestTaskAt() == null || openedAt.isAfter(card.getLatestTaskAt())) {
            card.setLatestTaskAt(openedAt);
        }
    }

    private void fillDiseases(CockpitFocusDto dto, String tenantId, String peopleId) {
        List<String> codes = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (DiseaseArchiveViewDto row : diseaseArchiveService.list(tenantId, peopleId)) {
            if (!StringUtils.hasText(row.getDiseaseCode())) {
                continue;
            }
            codes.add(row.getDiseaseCode());
            labels.add(DiseaseCodeLabels.label(row.getDiseaseCode()));
        }
        dto.setDiseaseCodes(codes);
        dto.setDiseaseLabels(labels);
    }

    private void fillArchiveCompleteness(CockpitFocusDto dto, String tenantId, String peopleId) {
        try {
            ArchiveCompletenessDto c = archiveCompletenessService.compute(tenantId, peopleId);
            dto.setArchiveCompletenessPercent(c.getPercent());
            dto.setArchiveFilledCount(c.getFilledCount());
            dto.setArchiveTotalCount(c.getTotalCount());
        } catch (Exception ignored) {
            // 完整度失败不阻断焦点面板
        }
    }

    private void fillAssessmentTags(CockpitFocusDto dto, String tenantId, String orgId, String peopleId) {
        AssessmentOverviewDto overview;
        try {
            overview = assessmentOrchestrator.overview(tenantId, orgId, peopleId);
        } catch (Exception ignored) {
            return;
        }
        Map<String, AssessmentSnapshotDto> byCode = overview.getLatest().stream()
                .filter(s -> StringUtils.hasText(s.getEngineCode()))
                .collect(Collectors.toMap(
                        AssessmentSnapshotDto::getEngineCode, s -> s, (a, b) -> a, HashMap::new));
        Set<String> available = overview.getAvailableEngines().stream()
                .map(AssessmentOverviewDto.AvailableEngineDto::getEngineCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        AssessmentSnapshotDto control = byCode.get(CONTROL_ENGINE);
        if (control != null || available.contains(CONTROL_ENGINE)) {
            dto.getAssessmentTags().add(controlTag(control));
        }
        for (String[] meta : RISK_ENGINES) {
            String code = meta[0];
            String title = meta[1];
            AssessmentSnapshotDto snap = byCode.get(code);
            String label = "未评";
            if (snap != null && "INCOMPLETE".equals(snap.getStatus())) {
                label = "缺项";
            } else if (snap != null && StringUtils.hasText(snap.getLevelLabel())) {
                label = snap.getLevelLabel();
            } else if (snap != null && StringUtils.hasText(snap.getLevel())) {
                label = snap.getLevel();
            } else if (!available.contains(code)) {
                continue; // 不适用，不展示
            }
            boolean hasResult = snap != null
                    && !"INCOMPLETE".equals(snap.getStatus())
                    && StringUtils.hasText(snap.getLevel());
            String text;
            if ("CDRS".equals(code)) {
                text = hasResult || "缺项".equals(label) ? "糖尿病" + label : "糖尿病未评估";
            } else if ("HYPERTENSION_RISK".equals(code)) {
                text = hasResult || "缺项".equals(label) ? label : "高血压未评估";
            } else {
                text = hasResult || "缺项".equals(label) ? label : "肥胖未评估";
            }
            CockpitFocusDto.AssessmentTag tag = new CockpitFocusDto.AssessmentTag();
            tag.setEngineCode(code);
            tag.setText(text);
            tag.setTone(riskTone(snap));
            tag.setTitle(title);
            dto.getAssessmentTags().add(tag);
        }
    }

    private static CockpitFocusDto.AssessmentTag controlTag(AssessmentSnapshotDto snap) {
        CockpitFocusDto.AssessmentTag tag = new CockpitFocusDto.AssessmentTag();
        tag.setEngineCode(CONTROL_ENGINE);
        tag.setTitle("血糖控制分标");
        String level = snap == null ? null : snap.getLevel();
        String tone = "muted";
        if ("RED".equals(level)) {
            tone = "danger";
        } else if ("YELLOW".equals(level)) {
            tone = "warning";
        } else if ("GREEN".equals(level) || "NEAR_GREEN".equals(level)) {
            tone = "success";
        } else if ("NONE".equals(level)) {
            tone = "info";
        }
        String label;
        if (snap == null) {
            label = "未评估";
        } else if ("NONE".equals(level)) {
            label = "未分标";
        } else if (StringUtils.hasText(snap.getLevelLabel())) {
            label = snap.getLevelLabel();
        } else if (StringUtils.hasText(level)) {
            label = level;
        } else {
            label = "未评估";
        }
        tag.setText("血糖" + label);
        tag.setTone(tone);
        return tag;
    }

    private static String riskTone(AssessmentSnapshotDto snap) {
        if (snap == null || "INCOMPLETE".equals(snap.getStatus()) || !StringUtils.hasText(snap.getLevel())) {
            return "muted";
        }
        String level = snap.getLevel();
        if ("HIGH".equals(level)
                || "GRADE_3".equals(level)
                || "GRADE_2".equals(level)
                || level.contains("SEVERE")
                || "EXTREME_OBESITY".equals(level)) {
            return "danger";
        }
        if ("MID".equals(level)
                || "GRADE_1".equals(level)
                || "PREHYPERTENSION".equals(level)
                || "OVERWEIGHT".equals(level)
                || "MILD_OBESITY".equals(level)
                || "MODERATE_OBESITY".equals(level)) {
            return "warning";
        }
        return "success";
    }

    private String resolveBloodPressure(String tenantId, String orgId, String peopleId) {
        try {
            BigDecimal sys = null;
            BigDecimal dia = null;
            LocalDateTime sysAt = null;
            LocalDateTime diaAt = null;
            for (MetricLatestSlotDto slot : metricService.latest(tenantId, orgId, peopleId)) {
                if (MetricTypeEnum.BLOOD_PRESSURE_SYS.matches(slot.metricType())) {
                    if (sysAt == null || (slot.recordedAt() != null && slot.recordedAt().isAfter(sysAt))) {
                        sys = slot.value();
                        sysAt = slot.recordedAt();
                    }
                }
                if (MetricTypeEnum.BLOOD_PRESSURE_DIA.matches(slot.metricType())) {
                    if (diaAt == null || (slot.recordedAt() != null && slot.recordedAt().isAfter(diaAt))) {
                        dia = slot.value();
                        diaAt = slot.recordedAt();
                    }
                }
            }
            if (sys == null && dia == null) {
                return null;
            }
            String sysText = sys == null ? "-" : stripTrailingZero(sys);
            String diaText = dia == null ? "-" : stripTrailingZero(dia);
            return sysText + "/" + diaText + " mmHg";
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String stripTrailingZero(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private void fillTeamIfMissing(CockpitPriorityCardDto card, String orgId, String peopleId) {
        if (StringUtils.hasText(card.getCareTeamId())) {
            return;
        }
        CareTeamMember member = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        if (member == null) {
            return;
        }
        card.setCareTeamId(member.getTeamId());
        CareTeam team = careTeamMapper.findById(member.getTeamId());
        card.setCareTeamName(team == null ? null : team.getName());
    }

    private static void addBadge(CockpitPriorityCardDto card, String badge) {
        if (!StringUtils.hasText(badge)) {
            return;
        }
        Set<String> set = new LinkedHashSet<>(card.getBadges());
        set.add(badge);
        card.setBadges(new ArrayList<>(set));
    }

    private static boolean isOverdue(WorkspaceTask task) {
        return task.getDueAt() != null && task.getDueAt().isBefore(LocalDateTime.now(JobCronSupport.ZONE));
    }

    /**
     * 有效优先级：患者申请抬到 HIGH；其余用类型默认（不依赖历史全 HIGH 的库内字段）。
     */
    private static WorkspaceTaskPriority effectivePriority(WorkspaceTask task, WorkspaceTaskType type) {
        if ("PATIENT_REQUEST".equals(task.getSource())) {
            return WorkspaceTaskPriority.HIGH;
        }
        return type.defaultPriority();
    }

    private static void bumpMaxPriority(CockpitPriorityCardDto card, WorkspaceTaskPriority priority) {
        WorkspaceTaskPriority current = StringUtils.hasText(card.getMaxTaskPriority())
                ? WorkspaceTaskPriority.fromStored(card.getMaxTaskPriority())
                : null;
        WorkspaceTaskPriority next = WorkspaceTaskPriority.max(current, priority);
        if (next != null) {
            card.setMaxTaskPriority(next.name());
        }
    }

    /** 超期数×1_000_000 + 优先级权重×1_000 + 待办数 */
    private static int packUrgencyScore(CockpitPriorityCardDto card) {
        int overdue = Math.max(0, card.getOverdueTaskCount());
        int pri = WorkspaceTaskPriority.fromStored(card.getMaxTaskPriority()).weight();
        int open = Math.max(0, card.getOpenTaskCount());
        return overdue * 1_000_000 + pri * 1_000 + open;
    }

    private static String topReason(CockpitPriorityCardDto card) {
        List<String> b = card.getBadges();
        if (b.contains("PATIENT_REQUEST")) {
            return "患者申请回访";
        }
        if (b.contains("OVERDUE")) {
            return "任务已超期";
        }
        if (b.contains("METRIC_ALERT")) {
            return "指标异常待跟进";
        }
        if (b.contains("PLAN_NUDGE")) {
            return "打卡跟进";
        }
        if (b.contains("FOLLOW_UP")) {
            return "定期随访";
        }
        if (b.contains("PLAN_CREATE")) {
            return "待制定方案";
        }
        if (b.contains("PLAN_REVIEW")) {
            return "方案待复核";
        }
        if (b.contains("REPORT_REVIEW")) {
            return "报告待审阅";
        }
        if (b.contains("TEAM_ASSIGN")) {
            return "待分配健管组";
        }
        return "今日关注";
    }

    /**
     * bucket 仅用于「今日关注」细分：高紧迫进 urgent，其余进 watch。
     * 「立即处理 / 我的在办」Tab 都会展示我名下全部在办（见 {@link #matchTab}）。
     */
    private static String classifyBucket(CockpitPriorityCardDto card) {
        List<String> b = card.getBadges();
        if (b.contains("PATIENT_REQUEST")
                || b.contains("OVERDUE")
                || b.contains("METRIC_ALERT")
                || WorkspaceTaskPriority.HIGH.name().equals(card.getMaxTaskPriority())) {
            return "urgent";
        }
        return "watch";
    }

    private static boolean matchTab(String tab, CockpitPriorityCardDto card) {
        // 领取后多为 MEDIUM（随访/打卡等）：默认「立即处理」与「我的在办」都展示全部，再按 urgencyScore 排序
        if ("urgent".equals(tab) || "mine".equals(tab)) {
            return card.getOpenTaskCount() > 0;
        }
        if ("watch".equals(tab)) {
            return "watch".equals(card.getBucket());
        }
        return true;
    }

    private static String normalizeTab(String tab) {
        if (!StringUtils.hasText(tab)) {
            return "urgent";
        }
        String t = tab.trim().toLowerCase(Locale.ROOT);
        if ("urgent".equals(t) || "watch".equals(t) || "mine".equals(t)) {
            return t;
        }
        throw new BusinessException(400, "未知 tab，可选 urgent / watch / mine");
    }

    private List<String> doctorPublicExclude() {
        Set<String> roles = currentRoles();
        boolean doctorOnly = roles.contains(StaffRoleEnum.DOCTOR.name())
                && !roles.contains(StaffRoleEnum.CARE_MANAGER.name())
                && !roles.contains(StaffRoleEnum.TENANT_ADMIN.name());
        if (!doctorOnly) {
            return List.of();
        }
        List<String> exclude = new ArrayList<>();
        for (WorkspaceTaskType type : WorkspaceTaskType.values()) {
            if (type.doctorPublicHidden()) {
                exclude.add(type.name());
            }
        }
        return exclude;
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return ctx.getRoles();
    }

    private static String requireStaffId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            throw new BusinessException(401, "缺少员工上下文");
        }
        return ctx.getStaffId();
    }
}
