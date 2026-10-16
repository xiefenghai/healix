package com.healix.core.cockpit.service;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.exception.BusinessException;
import com.healix.core.adherence.dto.AdherenceDayPointDto;
import com.healix.core.adherence.dto.AdherenceLifestyleMetricsDto;
import com.healix.core.adherence.dto.AdherencePatientDetailDto;
import com.healix.core.adherence.dto.AdherencePatientItemDto;
import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.allergy.service.AllergyQueryService;
import com.healix.core.archive.dto.ArchiveCompletenessDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveCompletenessService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.assessment.dto.AssessmentTagView;
import com.healix.core.assessment.support.AssessmentTagAssembler;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.cockpit.dto.CockpitDraftInboxItemDto;
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
import com.healix.core.workspace.mapper.StaffPatientWatchMapper;
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
import com.healix.core.reward.dto.RewardViews.CockpitWeek;
import com.healix.core.reward.service.RewardQueryService;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final String PATIENT_REQUEST_LABEL = "申请回访";
    private static final String PLAN_RENEW_LABEL = "方案待复评";
    private static final int BP_HIGH_SYS = 140;
    private static final int BP_HIGH_DIA = 90;

    private final OrgWorkspaceService orgWorkspaceService;
    private final AdherenceQueryService adherenceQueryService;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final CareTeamMapper careTeamMapper;
    private final ArchiveAccessService archiveAccessService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final ArchiveCompletenessService archiveCompletenessService;
    private final AssessmentTagAssembler assessmentTagAssembler;
    private final MetricService metricService;
    private final CarePlanService carePlanService;
    private final HealthReportService healthReportService;
    private final StaffPatientWatchMapper staffPatientWatchMapper;
    private final ObjectProvider<RewardQueryService> rewardQueryService;
    private final AllergyQueryService allergyQueryService;

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
        int mine = 0;
        for (CockpitPriorityCardDto card : all) {
            overdue += Math.max(0, card.getOverdueTaskCount());
            if (matchTab("urgent", card)) {
                urgent++;
            }
            if (matchTab("mine", card)) {
                mine++;
            }
        }
        int pendingPlan = 0;
        int pendingReport = 0;
        for (WorkspaceTask t : loadMineOpenTasks(tenantId, orgId, staffId)) {
            if (t == null || !StringUtils.hasText(t.getTaskType())) {
                continue;
            }
            if (WorkspaceTaskType.PLAN_CREATE.matches(t.getTaskType())
                    || WorkspaceTaskType.PLAN_REVIEW.matches(t.getTaskType())) {
                pendingPlan++;
            } else if (WorkspaceTaskType.REPORT_REVIEW.matches(t.getTaskType())) {
                pendingReport++;
            }
        }
        dto.setOverdueCount(overdue);
        dto.setUrgentCount(urgent);
        // 「我的关注」= 个人重点关注人数，与任务分桶无关
        dto.setWatchCount(staffPatientWatchMapper.countByStaffOrg(staffId, orgId));
        dto.setMineCount(mine);
        dto.setPendingCarePlanDraftCount(pendingPlan);
        dto.setPendingReportDraftCount(pendingReport);
        RewardQueryService rewards = rewardQueryService.getIfAvailable();
        if (rewards != null) {
            CockpitWeek week = rewards.cockpitWeek(tenantId, orgId);
            dto.setRewardWeekPoints(week.weekPoints());
            dto.setRewardFullDayCount(week.fullDayCount());
            dto.setRewardActiveTodayCount(week.activeTodayCount());
        }
        return dto;
    }

    /**
     * 左栏优先患者。
     *
     * @param tab urgent = 我的高优在办；watch = 个人重点关注患者；mine = 我的全部在办；默认 urgent
     */
    public List<CockpitPriorityCardDto> priority(String tenantId, String orgId, String tab) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        String bucket = normalizeTab(tab);
        if ("watch".equals(bucket)) {
            return buildWatchedCards(orgId, staffId);
        }
        return buildPriorityCards(tenantId, orgId, staffId).stream()
                .filter(c -> matchTab(bucket, c))
                .toList();
    }

    /**
     * 我的患者：当前员工作为主责健管师的健管组下全部患者（可按姓名关键字筛选）。
     * 卡片字段与「立即处理 / 我的关注」对齐；无待办也可列出，供驾驶舱注入对话。
     */
    public List<CockpitPriorityCardDto> myPatients(String orgId, String keyword) {
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
        return buildPatientRosterCards(
                orgId, staffId, new ArrayList<>(byPeople.values()), "mine", "主责患者", null);
    }

    /** 个人重点关注患者卡片（可无待办）。 */
    private List<CockpitPriorityCardDto> buildWatchedCards(String orgId, String staffId) {
        List<OrgPatientListItem> watched =
                orgWorkspaceService.listOrgPatients(orgId, null, null, null, true);
        return buildPatientRosterCards(orgId, staffId, watched, "watch", "重点关注", "WATCHED");
    }

    /**
     * 将机构患者列表叠上名下 OPEN 任务，产出与优先卡同结构的左栏卡片。
     *
     * @param extraBadge 额外徽章（如 WATCHED）；可为 null
     * @param defaultReason 无任务时的兜底说明
     */
    private List<CockpitPriorityCardDto> buildPatientRosterCards(
            String orgId,
            String staffId,
            List<OrgPatientListItem> patients,
            String bucket,
            String defaultReason,
            String extraBadge) {
        if (patients == null || patients.isEmpty()) {
            return List.of();
        }
        Map<String, CockpitPriorityCardDto> taskByPeople = new HashMap<>();
        for (CockpitPriorityCardDto card : buildPriorityCards(requireTenantId(), orgId, staffId)) {
            taskByPeople.put(card.getPeopleId(), card);
        }
        List<CockpitPriorityCardDto> out = new ArrayList<>();
        for (OrgPatientListItem p : patients) {
            if (p == null || !StringUtils.hasText(p.getPeopleId())) {
                continue;
            }
            CockpitPriorityCardDto fromTask = taskByPeople.get(p.getPeopleId());
            CockpitPriorityCardDto card;
            if (fromTask != null) {
                card = fromTask;
                if (!StringUtils.hasText(card.getCareTeamName()) && StringUtils.hasText(p.getCareTeamName())) {
                    card.setCareTeamId(p.getCareTeamId());
                    card.setCareTeamName(p.getCareTeamName());
                }
                if (card.getClientLinked() == null) {
                    card.setClientLinked(p.getClientLinked());
                }
            } else {
                card = ensureCard(new HashMap<>(), p.getPeopleId(), p.getDisplayName());
                card.setDisplayName(p.getDisplayName());
                card.setGender(p.getGender());
                fillProfile(card);
                fillCardDiseases(card, requireTenantId());
                card.setCareTeamId(p.getCareTeamId());
                card.setCareTeamName(p.getCareTeamName());
                card.setClientLinked(p.getClientLinked());
                card.setOpenTaskCount(0);
                card.setOverdueTaskCount(0);
                card.setUrgencyScore(0);
            }
            if (StringUtils.hasText(extraBadge)) {
                addBadge(card, extraBadge);
            }
            card.setBucket(bucket);
            if (!StringUtils.hasText(card.getTopReason()) || "今日关注".equals(card.getTopReason())) {
                if (StringUtils.hasText(defaultReason)) {
                    card.setTopReason(defaultReason);
                } else if (StringUtils.hasText(p.getCareTeamName())) {
                    card.setTopReason(p.getCareTeamName());
                }
            }
            out.add(card);
        }
        out.sort(Comparator.comparingInt(CockpitPriorityCardDto::getUrgencyScore)
                .reversed()
                .thenComparing(c -> c.getDisplayName() == null ? "" : c.getDisplayName()));
        return out;
    }

    private static String requireTenantId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new BusinessException(401, "缺少租户上下文");
        }
        return ctx.getTenantId();
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
            String label = taskLabel(task, type);
            if ("PATIENT_REQUEST".equals(task.getSource())) {
                addTaskType(card, type, PATIENT_REQUEST_LABEL);
            }
            addTaskType(card, type, label);
            if (StringUtils.hasText(task.getSummary()) && !label.equals(task.getSummary().trim())) {
                card.getReasonDetails().add(label + "：" + task.getSummary().trim());
            } else {
                card.getReasonDetails().add(label);
            }
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
            fillCardDiseases(card, tenantId);
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
        fillMedWindow(dto, detail);
        fillLifestyle(dto, tenantId, peopleId);
        dto.setAllergyText(resolveAllergyText(tenantId, peopleId));
        dto.setArchivePath("/workspace/patients/" + peopleId + "/archive");
        dto.setCareChatPath("/workspace/care-chat/" + peopleId);
        fillDiseases(dto, tenantId, peopleId);
        BloodPressure bp = resolveBloodPressure(tenantId, orgId, peopleId);
        if (bp != null) {
            dto.setBloodPressure(bp.text());
            dto.setBloodPressureAt(bp.recordedAt());
            dto.setBloodPressureHigh(bp.high());
        }
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
            brief.setDueAt(task.getDueAt());
            brief.setTaskTypeLabel(taskLabel(task, type));
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
                d.setActionLabel("去审阅方案");
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
                d.setActionLabel(StringUtils.hasText(comment) ? "去审阅发布" : "去补点评");
                dto.getPendingDrafts().add(d);
            }
        } catch (Exception ignored) {
            // ignore
        }
    }

    /** 机构级草稿箱：未选患者时右栏展示，超期优先、到期早的在前。 */
    public List<CockpitDraftInboxItemDto> draftInbox(String tenantId, String orgId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        Map<String, String> names = new HashMap<>();
        List<CockpitDraftInboxItemDto> out = new ArrayList<>();
        for (WorkspaceTask task : loadMineOpenTasks(tenantId, orgId, staffId)) {
            if (task == null || !StringUtils.hasText(task.getPeopleId())) {
                continue;
            }
            WorkspaceTaskType type;
            try {
                type = WorkspaceTaskType.require(task.getTaskType());
            } catch (IllegalArgumentException e) {
                continue;
            }
            CockpitDraftInboxItemDto item = new CockpitDraftInboxItemDto();
            switch (type) {
                case PLAN_CREATE -> {
                    item.setKind("CARE_PLAN");
                    item.setSheetMode("care-plan");
                    item.setActionLabel("去制定");
                }
                case PLAN_REVIEW -> {
                    item.setKind("CARE_PLAN");
                    item.setSheetMode("care-plan");
                    item.setActionLabel("去复核");
                }
                case REPORT_REVIEW -> {
                    item.setKind("REPORT");
                    item.setSheetMode("reports");
                    item.setActionLabel("去审阅");
                    item.setReportId(task.getBizKey());
                }
                default -> {
                    continue;
                }
            }
            item.setTaskId(task.getId());
            item.setTaskType(type.name());
            item.setTaskTypeLabel(type.label());
            item.setPeopleId(task.getPeopleId());
            item.setDisplayName(names.computeIfAbsent(task.getPeopleId(), id -> {
                PeopleProfile p = peopleProfileMapper.findById(id);
                return p == null || !StringUtils.hasText(p.getDisplayName()) ? "未命名" : p.getDisplayName();
            }));
            item.setSummary(task.getSummary());
            item.setOverdue(isOverdue(task));
            item.setDueAt(task.getDueAt());
            out.add(item);
        }
        out.sort(Comparator.comparing(CockpitDraftInboxItemDto::isOverdue)
                .reversed()
                .thenComparing(
                        CockpitDraftInboxItemDto::getDueAt, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
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
        if (StringUtils.hasText(card.getGender())
                && StringUtils.hasText(card.getDisplayName())
                && card.getAge() != null) {
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
        if (card.getAge() == null && p.getBirthday() != null) {
            card.setAge(Period.between(p.getBirthday(), LocalDate.now(JobCronSupport.ZONE)).getYears());
        }
    }

    private void fillCardDiseases(CockpitPriorityCardDto card, String tenantId) {
        if (!card.getDiseaseLabels().isEmpty()) {
            return;
        }
        try {
            for (DiseaseArchiveViewDto row : diseaseArchiveService.list(tenantId, card.getPeopleId())) {
                if (StringUtils.hasText(row.getDiseaseCode())) {
                    card.getDiseaseLabels().add(DiseaseCodeLabels.label(row.getDiseaseCode()));
                }
            }
        } catch (Exception ignored) {
            // 病种读取失败不影响卡片
        }
    }

    /** 方案到期前由扫描开出的 PLAN_CREATE 显示为「方案待复评」。 */
    private static String taskLabel(WorkspaceTask task, WorkspaceTaskType type) {
        if (type == WorkspaceTaskType.PLAN_CREATE
                && StringUtils.hasText(task.getPayloadJson())
                && task.getPayloadJson().contains("\"renew\":true")) {
            return PLAN_RENEW_LABEL;
        }
        return type.label();
    }

    private static void addTaskType(CockpitPriorityCardDto card, WorkspaceTaskType type, String label) {
        Set<String> codes = new LinkedHashSet<>(card.getTaskTypes());
        Set<String> labels = new LinkedHashSet<>(card.getTaskTypeLabels());
        codes.add(type.name());
        labels.add(label);
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
        for (AssessmentTagView view : assessmentTagAssembler.assemble(tenantId, orgId, peopleId)) {
            CockpitFocusDto.AssessmentTag tag = new CockpitFocusDto.AssessmentTag();
            tag.setEngineCode(view.getEngineCode());
            tag.setDiseaseCode(view.getDiseaseCode());
            tag.setCategory(view.getCategory());
            tag.setTier(view.getTier());
            tag.setText(view.getText());
            tag.setTone(view.getTone());
            tag.setTitle(view.getTitle());
            dto.getAssessmentTags().add(tag);
        }
    }

    private record BloodPressure(String text, LocalDateTime recordedAt, boolean high) {}

    private void fillMedWindow(CockpitFocusDto dto, AdherencePatientDetailDto detail) {
        List<AdherenceDayPointDto> days = detail.getDays();
        if (days == null || days.isEmpty()) {
            return;
        }
        int due = 0;
        int taken = 0;
        int from = Math.max(0, days.size() - 7);
        for (int i = from; i < days.size(); i++) {
            due += days.get(i).getMedDue();
            taken += days.get(i).getMedTakenDose();
        }
        if (due > 0) {
            dto.setMedRate7d(Math.round(100.0d * taken / due) / 100.0d);
        }
        int streak = 0;
        for (int i = days.size() - 1; i >= 0; i--) {
            AdherenceDayPointDto d = days.get(i);
            if (d.getMedDue() <= 0) {
                continue;
            }
            if (!d.isMedIncomplete()) {
                break;
            }
            streak++;
        }
        dto.setMedMissStreakDays(streak);
    }

    private void fillLifestyle(CockpitFocusDto dto, String tenantId, String peopleId) {
        try {
            AdherenceLifestyleMetricsDto ls =
                    adherenceQueryService.lifestyle7d(tenantId, peopleId, LocalDate.now(JobCronSupport.ZONE));
            dto.setDietDays7d(ls.getDietDays7d());
            dto.setExerciseDays7d(ls.getExerciseDays7d());
        } catch (Exception ignored) {
            // 生活方式读取失败不阻断焦点面板
        }
    }

    private String resolveAllergyText(String tenantId, String peopleId) {
        try {
            AllergyStatusEnum status = allergyQueryService.status(tenantId, peopleId);
            if (status == AllergyStatusEnum.HAS) {
                List<String> names = allergyQueryService.activeNames(peopleId);
                return names.isEmpty() ? status.label() : String.join("、", names);
            }
            return status.label();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 近 7 日方案完成率最低的客户（仅含执行中方案），升序。 */
    public List<AdherencePatientItemDto> lowestAdherence(String tenantId, String orgId, int limit) {
        return myUniverse(tenantId, orgId).stream()
                .filter(i -> i.getPlan() != null && i.getPlan().isHasActivePlan() && i.getPlan().getRate7d() != null)
                .sorted(Comparator.comparingDouble((AdherencePatientItemDto i) -> i.getPlan().getRate7d())
                        .thenComparing(i -> -i.getPlan().getStreakDays()))
                .limit(Math.max(1, limit))
                .toList();
    }

    /** 方案执行期在 withinDays 天内结束的客户，按到期日升序。 */
    public List<AdherencePatientItemDto> plansEndingSoon(String tenantId, String orgId, int withinDays) {
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        LocalDate until = today.plusDays(Math.max(0, withinDays));
        return myUniverse(tenantId, orgId).stream()
                .filter(i -> i.getPlanEndDate() != null
                        && !i.getPlanEndDate().isBefore(today)
                        && !i.getPlanEndDate().isAfter(until))
                .sorted(Comparator.comparing(AdherencePatientItemDto::getPlanEndDate))
                .toList();
    }

    /** 主责健管组下的客户；不是任何组主责时退回机构可见范围。 */
    private List<AdherencePatientItemDto> myUniverse(String tenantId, String orgId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        List<AdherencePatientItemDto> all =
                adherenceQueryService.listUniverse(tenantId, orgId, LocalDate.now(JobCronSupport.ZONE));
        Set<String> teamIds = new LinkedHashSet<>();
        for (CareTeam team : careTeamMapper.listByPrimaryCareManager(orgId, staffId)) {
            teamIds.add(team.getId());
        }
        if (teamIds.isEmpty()) {
            return all;
        }
        return all.stream().filter(i -> teamIds.contains(i.getCareTeamId())).toList();
    }

    /** 简报用：某人近况事实短句（最新血压、打卡 / 服药连续未完成）。 */
    public List<String> briefFacts(String tenantId, String orgId, String peopleId) {
        List<String> out = new ArrayList<>();
        BloodPressure bp = resolveBloodPressure(tenantId, orgId, peopleId);
        if (bp != null && bp.recordedAt() != null
                && !bp.recordedAt().toLocalDate().isBefore(LocalDate.now(JobCronSupport.ZONE).minusDays(2))) {
            out.add("最近血压 " + bp.text() + (bp.high() ? "（偏高）" : "")
                    + "，测于 " + bp.recordedAt().toLocalDate() + " " + bp.recordedAt().toLocalTime().withNano(0).withSecond(0));
        }
        try {
            AdherencePatientDetailDto detail = adherenceQueryService.patientDetail(tenantId, orgId, peopleId, null, 7);
            if (detail.getPlan() != null && detail.getPlan().getStreakDays() >= 2) {
                out.add("方案打卡连续 " + detail.getPlan().getStreakDays() + " 天未完成");
            }
            CockpitFocusDto tmp = new CockpitFocusDto();
            fillMedWindow(tmp, detail);
            if (tmp.getMedMissStreakDays() >= 2) {
                out.add("服药连续 " + tmp.getMedMissStreakDays() + " 天未按时完成");
            }
        } catch (Exception ignored) {
            // 依从读取失败时只给血压
        }
        return out;
    }

    private BloodPressure resolveBloodPressure(String tenantId, String orgId, String peopleId) {
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
            boolean high = (sys != null && sys.intValue() >= BP_HIGH_SYS)
                    || (dia != null && dia.intValue() >= BP_HIGH_DIA);
            LocalDateTime at = sysAt != null ? sysAt : diaAt;
            return new BloodPressure(sysText + "/" + diaText + " mmHg", at, high);
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
        if (card.getTaskTypeLabels().contains(PLAN_RENEW_LABEL)) {
            return PLAN_RENEW_LABEL;
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
     * bucket 仅用于「立即处理」内部区分高紧迫；「我的关注」已改为个人重点关注列表，不再用此分桶。
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
        // watch 走独立的 buildWatchedCards，不经过此处
        if ("urgent".equals(tab) || "mine".equals(tab)) {
            return card.getOpenTaskCount() > 0;
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
