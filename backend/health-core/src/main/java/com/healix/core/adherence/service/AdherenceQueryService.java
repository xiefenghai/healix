package com.healix.core.adherence.service;

import com.healix.common.result.PageResult;
import com.healix.core.adherence.dto.AdherenceDayPointDto;
import com.healix.core.adherence.dto.AdherenceMedMetricsDto;
import com.healix.core.adherence.dto.AdherenceOverviewDto;
import com.healix.core.adherence.dto.AdherencePatientDetailDto;
import com.healix.core.adherence.dto.AdherencePatientItemDto;
import com.healix.core.adherence.dto.AdherencePatientSummaryDto;
import com.healix.core.adherence.dto.AdherencePlanMetricsDto;
import com.healix.core.adherence.dto.AdherenceTodayMedItemDto;
import com.healix.core.adherence.dto.AdherenceTodayTaskItemDto;
import com.healix.core.adherence.enums.AdherenceFilterEnum;
import com.healix.core.adherence.enums.AdherenceRiskLevelEnum;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.domain.CarePlanTaskCheckin;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.core.careplan.enums.CarePlanCheckinStatusEnum;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanTaskCheckinMapper;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.careplan.support.CarePlanDueSupport;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationFrequencySupport;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.service.OrgWorkspaceService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 依从性读模型：看板、驾驶舱优先名单与日待办摘要共用。
 */
@Service
@RequiredArgsConstructor
public class AdherenceQueryService {

    private static final int STREAK_LOOKBACK_DAYS = 30;
    private static final int RATE_WINDOW_DAYS = 7;
    private static final int STREAK_HIGH_THRESHOLD = 3;
    private static final double MEDIUM_RATE_THRESHOLD = 0.60d;
    private static final int DETAIL_WINDOW_DAYS_DEFAULT = 14;
    private static final int DETAIL_WINDOW_DAYS_MAX = 30;

    private final OrgWorkspaceService orgWorkspaceService;
    private final CarePlanMapper carePlanMapper;
    private final CarePlanVersionMapper versionMapper;
    private final CarePlanTaskMapper taskMapper;
    private final CarePlanTaskCheckinMapper checkinMapper;
    private final PeopleMedicationMapper medicationMapper;
    private final PeopleMedicationIntakeMapper intakeMapper;

    public AdherenceOverviewDto overview(String tenantId, String orgId, LocalDate date, String careTeamId) {
        LocalDate day = date != null ? date : LocalDate.now();
        List<AdherencePatientItemDto> all = buildUniverse(tenantId, orgId, day, careTeamId, null);
        AdherenceOverviewDto out = new AdherenceOverviewDto();
        out.setDate(day);
        out.setCareTeamId(StringUtils.hasText(careTeamId) ? careTeamId : null);
        out.setUniverseCount(all.size());
        int followUp = 0;
        int planIncomplete = 0;
        int medIncomplete = 0;
        int streakGe3 = 0;
        for (AdherencePatientItemDto item : all) {
            if (AdherenceRiskLevelEnum.HIGH.name().equals(item.getRiskLevel())) {
                followUp++;
            }
            if (item.getPlan() != null && item.getPlan().isTodayIncomplete()) {
                planIncomplete++;
            }
            if (item.getMed() != null && item.getMed().isTodayIncomplete()) {
                medIncomplete++;
            }
            if (item.getPlan() != null && item.getPlan().getStreakDays() >= STREAK_HIGH_THRESHOLD) {
                streakGe3++;
            }
        }
        out.setFollowUpCount(followUp);
        out.setPlanIncompleteCount(planIncomplete);
        out.setMedIncompleteCount(medIncomplete);
        out.setStreakGe3Count(streakGe3);
        return out;
    }

    /** 日快照聚合：人数口径与 {@link #overview} 一致，另带方案/用药的分子分母求和。 */
    public record SnapshotAggregate(
            int universeCount,
            int followUpCount,
            int planIncompleteCount,
            int medIncompleteCount,
            int streakGe3Count,
            int planDueSum,
            int planDoneSum,
            int medDueDoseSum,
            int medTakenDoseSum) {}

    /**
     * 供快照 Job 调用：不校验工作台权限，直接按机构（可选健管组）汇总某一天。
     */
    public SnapshotAggregate aggregateForSnapshot(
            String tenantId, String orgId, LocalDate day, String careTeamId) {
        // Job 无登录态：走 system 患者列表，避免 RequestContext 缺失
        List<AdherencePatientItemDto> all = buildUniverse(tenantId, orgId, day, careTeamId, null, true);
        int followUp = 0;
        int planIncomplete = 0;
        int medIncomplete = 0;
        int streakGe3 = 0;
        int planDue = 0;
        int planDone = 0;
        int medDueDoses = 0;
        int medTakenDoses = 0;
        for (AdherencePatientItemDto item : all) {
            if (AdherenceRiskLevelEnum.HIGH.name().equals(item.getRiskLevel())) {
                followUp++;
            }
            AdherencePlanMetricsDto plan = item.getPlan();
            if (plan != null) {
                if (plan.isTodayIncomplete()) {
                    planIncomplete++;
                }
                if (plan.getStreakDays() >= STREAK_HIGH_THRESHOLD) {
                    streakGe3++;
                }
                planDue += plan.getDue();
                planDone += plan.getDone();
            }
            AdherenceMedMetricsDto med = item.getMed();
            if (med != null) {
                if (med.isTodayIncomplete()) {
                    medIncomplete++;
                }
                medDueDoses += med.getDueDoseCount();
                medTakenDoses += med.getTakenDoseCount();
            }
        }
        return new SnapshotAggregate(
                all.size(),
                followUp,
                planIncomplete,
                medIncomplete,
                streakGe3,
                planDue,
                planDone,
                medDueDoses,
                medTakenDoses);
    }

    public PageResult<AdherencePatientItemDto> pagePatients(
            String tenantId,
            String orgId,
            LocalDate date,
            String careTeamId,
            String risk,
            String filter,
            String keyword,
            int page,
            int size) {
        LocalDate day = date != null ? date : LocalDate.now();
        List<AdherencePatientItemDto> all = buildUniverse(tenantId, orgId, day, careTeamId, keyword);
        AdherenceFilterEnum filterEnum = parseFilter(filter);
        AdherenceRiskLevelEnum riskEnum = parseRisk(risk);
        List<AdherencePatientItemDto> filtered = all.stream()
                .filter(item -> matchRisk(item, riskEnum))
                .filter(item -> matchFilter(item, filterEnum))
                .sorted(patientComparator())
                .toList();
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        int from = (safePage - 1) * safeSize;
        if (from >= filtered.size()) {
            return new PageResult<>(filtered.size(), List.of());
        }
        int to = Math.min(filtered.size(), from + safeSize);
        return new PageResult<>(filtered.size(), filtered.subList(from, to));
    }

    public record PlanStreakSnapshot(boolean hasActivePlan, int streakDays) {}

    /** 日提醒 Job：当日方案或用药未完成的患者候选。 */
    public record DailyHealthTodoCandidate(
            String peopleId,
            String displayName,
            int planDue,
            int planIncomplete,
            boolean planIncompleteFlag,
            int medActive,
            int medTaken,
            int medDueDoses,
            int medTakenDoses,
            boolean medIncompleteFlag) {}

    /**
     * 日提醒 Job：对指定就诊人批量计算当日是否有方案/用药未完成。
     * 口径与依从性看板一致；不校验工作台权限。
     */
    public List<DailyHealthTodoCandidate> listDailyHealthTodos(
            String tenantId, List<String> peopleIds, Map<String, String> displayNameByPeople, LocalDate day) {
        if (peopleIds == null || peopleIds.isEmpty()) {
            return List.of();
        }
        LocalDate scanDay = day != null ? day : LocalDate.now();
        List<CarePlan> plans = carePlanMapper.listActiveByTenantAndPeopleIds(tenantId, peopleIds);
        Map<String, CarePlan> planByPeople = new HashMap<>();
        List<String> versionIds = new ArrayList<>();
        for (CarePlan plan : plans) {
            planByPeople.put(plan.getPeopleId(), plan);
            if (StringUtils.hasText(plan.getCurrentVersionId())) {
                versionIds.add(plan.getCurrentVersionId());
            }
        }
        Map<String, CarePlanVersion> versionById = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (CarePlanVersion v : versionMapper.listByIds(versionIds)) {
                versionById.put(v.getId(), v);
            }
        }

        List<CarePlanTask> tasks = taskMapper.listActiveByTenantAndPeopleIds(tenantId, peopleIds);
        Map<String, List<CarePlanTask>> tasksByPeople =
                tasks.stream().collect(Collectors.groupingBy(CarePlanTask::getPeopleId));

        List<CarePlanTaskCheckin> checkins =
                checkinMapper.listByTenantPeopleIdsRange(tenantId, peopleIds, scanDay, scanDay);
        Map<String, Map<LocalDate, Map<String, String>>> checkinStatus = indexCheckinStatus(checkins);

        List<PeopleMedication> meds = medicationMapper.listActiveByTenantAndPeopleIds(tenantId, peopleIds);
        Map<String, List<PeopleMedication>> medsByPeople =
                meds.stream().collect(Collectors.groupingBy(PeopleMedication::getPeopleId));

        List<PeopleMedicationIntake> intakes =
                intakeMapper.listByTenantPeopleIdsDate(tenantId, peopleIds, scanDay);
        Map<String, Map<String, Set<String>>> takenSlotsByPeople = indexTakenSlotsByPeople(intakes);

        List<DailyHealthTodoCandidate> out = new ArrayList<>();
        for (String peopleId : peopleIds) {
            CarePlan plan = planByPeople.get(peopleId);
            PlanWindow window = resolvePlanWindow(plan, versionById, scanDay);
            boolean planRecordExists = plan != null && StringUtils.hasText(plan.getCurrentVersionId());
            boolean hasActivePlan = planRecordExists
                    && CarePlanDueSupport.isWithinHorizon(scanDay, window.planStart(), window.horizonDays());
            List<CarePlanTask> peopleTasks = tasksByPeople.getOrDefault(peopleId, List.of());
            DayPlanStats todayPlan = hasActivePlan
                    ? calcPlanDay(
                            peopleTasks,
                            checkinStatus
                                    .getOrDefault(peopleId, Map.of())
                                    .getOrDefault(scanDay, Map.of()),
                            scanDay,
                            window.planStart(),
                            window.horizonDays())
                    : DayPlanStats.empty();
            AdherenceMedMetricsDto medMetrics = calcMedMetrics(
                    medsByPeople.getOrDefault(peopleId, List.of()),
                    takenSlotsByPeople.getOrDefault(peopleId, Map.of()),
                    scanDay);
            boolean planIncomplete = todayPlan.incompleteFlag;
            boolean medIncomplete = medMetrics.isTodayIncomplete();
            if (!planIncomplete && !medIncomplete) {
                continue;
            }
            String name = displayNameByPeople == null ? null : displayNameByPeople.get(peopleId);
            out.add(new DailyHealthTodoCandidate(
                    peopleId,
                    StringUtils.hasText(name) ? name.trim() : null,
                    todayPlan.due,
                    todayPlan.incomplete,
                    planIncomplete,
                    medMetrics.getActiveCount(),
                    medMetrics.getTakenCount(),
                    medMetrics.getDueDoseCount(),
                    medMetrics.getTakenDoseCount(),
                    medIncomplete));
        }
        return out;
    }

    /** 工作台扫描用：不依赖 RequestContext。 */
    public PlanStreakSnapshot lookupPlanStreak(String tenantId, String peopleId, LocalDate day) {
        PersonPlanContext ctx = loadPersonPlan(tenantId, peopleId, day);
        if (ctx == null || !ctx.hasActivePlan()) {
            return new PlanStreakSnapshot(false, 0);
        }
        int streak = calcStreak(
                ctx.tasks(), ctx.checkinsByDay(), day, ctx.window().planStart(), ctx.window().horizonDays());
        return new PlanStreakSnapshot(true, streak);
    }

    /**
     * 关单日之后是否存在一天：方案应打且当日已完成（done+skipped ≥ due 且 due&gt;0）。
     * 中间 due=0 的日历日跳过，不算打断。
     */
    public boolean hasCompletedDueDayAfter(
            String tenantId, String peopleId, LocalDate afterExclusive, LocalDate through) {
        if (afterExclusive == null || through == null || !through.isAfter(afterExclusive)) {
            return false;
        }
        PersonPlanContext ctx = loadPersonPlan(tenantId, peopleId, through);
        if (ctx == null || !ctx.hasActivePlan()) {
            return false;
        }
        List<CarePlanTaskCheckin> extra = checkinMapper.listByPeopleRange(
                tenantId, peopleId, afterExclusive.plusDays(1), through);
        Map<LocalDate, Map<String, String>> byDay =
                indexCheckinStatus(extra).getOrDefault(peopleId, new HashMap<>());
        ctx.checkinsByDay().forEach((d, m) -> byDay.putIfAbsent(d, m));
        LocalDate cursor = afterExclusive.plusDays(1);
        while (!cursor.isAfter(through)) {
            if (CarePlanDueSupport.isWithinHorizon(cursor, ctx.window().planStart(), ctx.window().horizonDays())) {
                DayPlanStats stats = calcPlanDay(
                        ctx.tasks(),
                        byDay.getOrDefault(cursor, Map.of()),
                        cursor,
                        ctx.window().planStart(),
                        ctx.window().horizonDays());
                if (stats.due > 0 && !stats.incompleteFlag) {
                    return true;
                }
            }
            cursor = cursor.plusDays(1);
        }
        return false;
    }

    private PersonPlanContext loadPersonPlan(String tenantId, String peopleId, LocalDate day) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan != null && !"ACTIVE".equals(plan.getStatus())) {
            plan = null;
        }
        Map<String, CarePlanVersion> versionById = new HashMap<>();
        if (plan != null && StringUtils.hasText(plan.getCurrentVersionId())) {
            CarePlanVersion version = versionMapper.findById(plan.getCurrentVersionId());
            if (version != null) {
                versionById.put(version.getId(), version);
            }
        }
        PlanWindow window = resolvePlanWindow(plan, versionById, day);
        boolean planRecordActive = plan != null && StringUtils.hasText(plan.getCurrentVersionId());
        boolean hasActivePlan = planRecordActive
                && CarePlanDueSupport.isWithinHorizon(day, window.planStart(), window.horizonDays());
        List<CarePlanTask> tasks = taskMapper.listActiveByPeople(tenantId, peopleId);
        LocalDate from = day.minusDays(STREAK_LOOKBACK_DAYS);
        List<CarePlanTaskCheckin> checkins =
                checkinMapper.listByTenantPeopleIdsRange(tenantId, List.of(peopleId), from, day);
        Map<String, Map<LocalDate, Map<String, String>>> indexed = indexCheckinStatus(checkins);
        return new PersonPlanContext(
                hasActivePlan, window, tasks, indexed.getOrDefault(peopleId, Map.of()));
    }

    private record PersonPlanContext(
            boolean hasActivePlan,
            PlanWindow window,
            List<CarePlanTask> tasks,
            Map<LocalDate, Map<String, String>> checkinsByDay) {}

    /**
     * 单患者依从性摘要（实时）：近 7 日方案完成率百分比 + 当日用药完成率，不返回明细列表。
     */
    public AdherencePatientSummaryDto patientSummary(
            String tenantId, String orgId, String peopleId, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now();
        // 校验患者属于当前机构宇宙
        orgWorkspaceService.getOrgPatient(orgId, peopleId);

        PersonPlanContext ctx = loadPersonPlan(tenantId, peopleId, day);
        boolean hasActivePlan = ctx.hasActivePlan();

        DayPlanStats todayPlan = DayPlanStats.empty();
        Double rate7d = null;
        int streak = 0;
        if (hasActivePlan) {
            todayPlan = calcPlanDay(
                    ctx.tasks(),
                    ctx.checkinsByDay().getOrDefault(day, Map.of()),
                    day,
                    ctx.window().planStart(),
                    ctx.window().horizonDays());
            rate7d = calcRate7d(
                    ctx.tasks(),
                    ctx.checkinsByDay(),
                    day,
                    ctx.window().planStart(),
                    ctx.window().horizonDays());
            streak = calcStreak(
                    ctx.tasks(),
                    ctx.checkinsByDay(),
                    day,
                    ctx.window().planStart(),
                    ctx.window().horizonDays());
        }

        List<PeopleMedication> meds = medicationMapper.listByPeople(tenantId, peopleId, "ACTIVE");
        List<PeopleMedicationIntake> intakes = intakeMapper.listByPeopleDate(tenantId, peopleId, day);
        Map<String, Set<String>> takenSlots = new HashMap<>();
        for (PeopleMedicationIntake intake : intakes) {
            if (MedicationIntakeStatusEnum.TAKEN.name().equals(intake.getStatus())) {
                takenSlots
                        .computeIfAbsent(intake.getMedicationId(), k -> new HashSet<>())
                        .add(slotKey(intake.getTimeSlot()));
            }
        }
        AdherenceMedMetricsDto medMetrics = calcMedMetrics(meds, takenSlots, day);

        AdherencePlanMetricsDto planMetrics = new AdherencePlanMetricsDto();
        planMetrics.setHasActivePlan(hasActivePlan);
        planMetrics.setDue(todayPlan.due);
        planMetrics.setDone(todayPlan.done);
        planMetrics.setSkipped(todayPlan.skipped);
        planMetrics.setIncomplete(todayPlan.incomplete);
        planMetrics.setTodayIncomplete(todayPlan.incompleteFlag);
        planMetrics.setStreakDays(streak);
        planMetrics.setRate7d(rate7d);

        AdherencePatientSummaryDto out = new AdherencePatientSummaryDto();
        out.setPeopleId(peopleId);
        out.setDate(day);
        out.setWindowDays(RATE_WINDOW_DAYS);
        out.setHasActivePlan(hasActivePlan);
        out.setStreakDays(streak);
        out.setRate(rate7d);
        out.setPercent(rate7d == null ? null : (int) Math.round(rate7d * 100.0d));
        out.setPlanTodayIncomplete(todayPlan.incompleteFlag);
        out.setMedTodayIncomplete(medMetrics.isTodayIncomplete());
        out.setMedPercent(
                medMetrics.getDueDoseCount() > 0
                        ? (int) Math.round(
                                100.0d * medMetrics.getTakenDoseCount() / (double) medMetrics.getDueDoseCount())
                        : null);
        out.setRiskLevel(resolveRisk(planMetrics, medMetrics).name());
        return out;
    }

    /** 单患者依从性详情：摘要 + 近日日曲线 + 当日任务/用药明细。 */
    public AdherencePatientDetailDto patientDetail(
            String tenantId, String orgId, String peopleId, LocalDate date, Integer windowDays) {
        LocalDate day = date != null ? date : LocalDate.now();
        int window = windowDays == null ? DETAIL_WINDOW_DAYS_DEFAULT : windowDays;
        window = Math.min(DETAIL_WINDOW_DAYS_MAX, Math.max(7, window));

        OrgPatientListItem patient = orgWorkspaceService.getOrgPatient(orgId, peopleId);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan != null && !"ACTIVE".equals(plan.getStatus())) {
            plan = null;
        }

        Map<String, CarePlanVersion> versionById = new HashMap<>();
        if (plan != null && StringUtils.hasText(plan.getCurrentVersionId())) {
            CarePlanVersion version = versionMapper.findById(plan.getCurrentVersionId());
            if (version != null) {
                versionById.put(version.getId(), version);
            }
        }
        PlanWindow windowCfg = resolvePlanWindow(plan, versionById, day);
        boolean planRecordActive = plan != null && StringUtils.hasText(plan.getCurrentVersionId());
        boolean hasActivePlan = planRecordActive
                && CarePlanDueSupport.isWithinHorizon(day, windowCfg.planStart(), windowCfg.horizonDays());

        List<CarePlanTask> tasks =
                planRecordActive ? taskMapper.listActiveByPeople(tenantId, peopleId) : List.of();

        LocalDate from = day.minusDays(window - 1L);
        List<CarePlanTaskCheckin> checkins =
                checkinMapper.listByPeopleRange(tenantId, peopleId, from, day);
        Map<LocalDate, Map<String, String>> checkinsByDay =
                indexCheckinStatus(checkins).getOrDefault(peopleId, Map.of());

        List<PeopleMedication> meds =
                medicationMapper.listByPeople(tenantId, peopleId, "ACTIVE");
        List<PeopleMedicationIntake> intakes =
                intakeMapper.listByPeopleRange(tenantId, peopleId, from, day);
        Map<LocalDate, Map<String, Set<String>>> takenByDay = new HashMap<>();
        for (PeopleMedicationIntake intake : intakes) {
            if (MedicationIntakeStatusEnum.TAKEN.name().equals(intake.getStatus())) {
                takenByDay
                        .computeIfAbsent(intake.getIntakeDate(), k -> new HashMap<>())
                        .computeIfAbsent(intake.getMedicationId(), k -> new HashSet<>())
                        .add(slotKey(intake.getTimeSlot()));
            }
        }

        List<AdherenceDayPointDto> dayPoints = new ArrayList<>();
        for (int i = window - 1; i >= 0; i--) {
            LocalDate d = day.minusDays(i);
            boolean planOnDay = planRecordActive
                    && CarePlanDueSupport.isWithinHorizon(d, windowCfg.planStart(), windowCfg.horizonDays());
            DayPlanStats planStats = planOnDay
                    ? calcPlanDay(
                            tasks,
                            checkinsByDay.getOrDefault(d, Map.of()),
                            d,
                            windowCfg.planStart(),
                            windowCfg.horizonDays())
                    : DayPlanStats.empty();
            AdherenceMedMetricsDto medDay =
                    calcMedMetrics(meds, takenByDay.getOrDefault(d, Map.of()), d);

            AdherenceDayPointDto point = new AdherenceDayPointDto();
            point.setDate(d);
            point.setPlanDue(planStats.due);
            point.setPlanDone(planStats.done);
            point.setPlanSkipped(planStats.skipped);
            point.setPlanIncomplete(planStats.incomplete);
            point.setPlanIncompleteFlag(planStats.incompleteFlag);
            point.setPlanDoneRate(
                    planStats.due > 0 ? round2((double) planStats.done / (double) planStats.due) : null);
            point.setMedActive(medDay.getActiveCount());
            point.setMedTaken(medDay.getTakenCount());
            point.setMedDue(medDay.getDueDoseCount());
            point.setMedTakenDose(medDay.getTakenDoseCount());
            point.setMedIncomplete(medDay.isTodayIncomplete());
            // 按次算率：BID 服一次记 50%
            point.setMedDoneRate(
                    medDay.getDueDoseCount() > 0
                            ? round2((double) medDay.getTakenDoseCount() / (double) medDay.getDueDoseCount())
                            : null);
            dayPoints.add(point);
        }

        DayPlanStats todayPlan = hasActivePlan
                ? calcPlanDay(
                        tasks,
                        checkinsByDay.getOrDefault(day, Map.of()),
                        day,
                        windowCfg.planStart(),
                        windowCfg.horizonDays())
                : DayPlanStats.empty();
        int streak = hasActivePlan
                ? calcStreak(tasks, checkinsByDay, day, windowCfg.planStart(), windowCfg.horizonDays())
                : 0;
        Double rate7d = hasActivePlan
                ? calcRate7d(tasks, checkinsByDay, day, windowCfg.planStart(), windowCfg.horizonDays())
                : null;
        AdherenceMedMetricsDto medMetrics =
                calcMedMetrics(meds, takenByDay.getOrDefault(day, Map.of()), day);

        AdherencePlanMetricsDto planMetrics = new AdherencePlanMetricsDto();
        planMetrics.setHasActivePlan(hasActivePlan);
        planMetrics.setDue(todayPlan.due);
        planMetrics.setDone(todayPlan.done);
        planMetrics.setSkipped(todayPlan.skipped);
        planMetrics.setIncomplete(todayPlan.incomplete);
        planMetrics.setTodayIncomplete(todayPlan.incompleteFlag);
        planMetrics.setStreakDays(streak);
        planMetrics.setRate7d(rate7d);

        List<AdherenceTodayTaskItemDto> todayTasks = new ArrayList<>();
        if (hasActivePlan) {
            Map<String, String> dayStatus = checkinsByDay.getOrDefault(day, Map.of());
            for (CarePlanTask task : tasks) {
                if (!CarePlanDueSupport.isDueOnDate(
                        task, day, windowCfg.planStart(), windowCfg.horizonDays())) {
                    continue;
                }
                String slot = CarePlanDueSupport.resolveTimeSlot(task.getTimeSlot());
                String status = dayStatus.get(task.getId() + "|" + slot);
                AdherenceTodayTaskItemDto item = new AdherenceTodayTaskItemDto();
                item.setTaskId(task.getId());
                item.setTitle(task.getTitle());
                item.setCategory(task.getCategory());
                item.setFrequency(task.getFrequency());
                item.setTimeSlot(task.getTimeSlot());
                if (CarePlanCheckinStatusEnum.DONE.name().equals(status)) {
                    item.setStatus(CarePlanCheckinStatusEnum.DONE.name());
                } else if (CarePlanCheckinStatusEnum.SKIPPED.name().equals(status)) {
                    item.setStatus(CarePlanCheckinStatusEnum.SKIPPED.name());
                } else {
                    item.setStatus("PENDING");
                }
                todayTasks.add(item);
            }
        }

        Map<String, Set<String>> takenToday = takenByDay.getOrDefault(day, Map.of());
        List<AdherenceTodayMedItemDto> todayMeds = new ArrayList<>();
        for (PeopleMedication med : meds) {
            if (!isMedActiveOnDay(med, day)) {
                continue;
            }
            Set<String> slots = takenToday.getOrDefault(med.getId(), Set.of());
            boolean prn = !MedicationFrequencySupport.isDueOnDay(med.getFrequency());
            int perDay = prn ? 0 : MedicationFrequencySupport.dosesPerDay(med.getFrequency());
            int done = prn ? slots.size() : Math.min(slots.size(), perDay);

            AdherenceTodayMedItemDto item = new AdherenceTodayMedItemDto();
            item.setMedicationId(med.getId());
            item.setDrugName(med.getDrugName());
            item.setFrequency(med.getFrequency());
            item.setDoseAmount(med.getDoseAmount());
            item.setDoseUnit(med.getDoseUnit());
            item.setDueDoseCount(perDay);
            item.setTakenDoseCount(done);
            item.setPrn(prn);
            item.setTakenSlots(List.copyOf(slots));
            item.setStatus(resolveMedItemStatus(prn, perDay, done));
            todayMeds.add(item);
        }

        AdherencePatientDetailDto out = new AdherencePatientDetailDto();
        out.setDate(day);
        out.setWindowDays(window);
        out.setPeopleId(peopleId);
        out.setDisplayName(patient.getDisplayName());
        out.setPlan(planMetrics);
        out.setMed(medMetrics);
        out.setRiskLevel(resolveRisk(planMetrics, medMetrics).name());
        out.setDays(dayPoints);
        out.setTodayTasks(todayTasks);
        out.setTodayMeds(todayMeds);
        return out;
    }

    private List<AdherencePatientItemDto> buildUniverse(
            String tenantId, String orgId, LocalDate day, String careTeamId, String keyword) {
        return buildUniverse(tenantId, orgId, day, careTeamId, keyword, false);
    }

    private List<AdherencePatientItemDto> buildUniverse(
            String tenantId,
            String orgId,
            LocalDate day,
            String careTeamId,
            String keyword,
            boolean systemCaller) {
        List<OrgPatientListItem> patients = systemCaller
                ? orgWorkspaceService.listOrgPatientsSystem(
                        tenantId, orgId, blankToNull(keyword), blankToNull(careTeamId), null)
                : orgWorkspaceService.listOrgPatients(
                        orgId, blankToNull(keyword), blankToNull(careTeamId), null);
        if (patients.isEmpty()) {
            return List.of();
        }
        List<String> peopleIds = patients.stream().map(OrgPatientListItem::getPeopleId).toList();

        List<CarePlan> plans = carePlanMapper.listActiveByTenantAndPeopleIds(tenantId, peopleIds);
        Map<String, CarePlan> planByPeople = new HashMap<>();
        List<String> versionIds = new ArrayList<>();
        for (CarePlan plan : plans) {
            planByPeople.put(plan.getPeopleId(), plan);
            if (StringUtils.hasText(plan.getCurrentVersionId())) {
                versionIds.add(plan.getCurrentVersionId());
            }
        }
        Map<String, CarePlanVersion> versionById = new HashMap<>();
        if (!versionIds.isEmpty()) {
            for (CarePlanVersion v : versionMapper.listByIds(versionIds)) {
                versionById.put(v.getId(), v);
            }
        }

        List<CarePlanTask> tasks = taskMapper.listActiveByTenantAndPeopleIds(tenantId, peopleIds);
        Map<String, List<CarePlanTask>> tasksByPeople = tasks.stream()
                .collect(Collectors.groupingBy(CarePlanTask::getPeopleId));

        LocalDate from = day.minusDays(STREAK_LOOKBACK_DAYS);
        List<CarePlanTaskCheckin> checkins =
                checkinMapper.listByTenantPeopleIdsRange(tenantId, peopleIds, from, day);
        // peopleId -> date -> (taskId|slot -> status)
        Map<String, Map<LocalDate, Map<String, String>>> checkinStatus = indexCheckinStatus(checkins);

        List<PeopleMedication> meds = medicationMapper.listActiveByTenantAndPeopleIds(tenantId, peopleIds);
        Map<String, List<PeopleMedication>> medsByPeople =
                meds.stream().collect(Collectors.groupingBy(PeopleMedication::getPeopleId));

        List<PeopleMedicationIntake> intakes =
                intakeMapper.listByTenantPeopleIdsDate(tenantId, peopleIds, day);
        Map<String, Map<String, Set<String>>> takenSlotsByPeople = indexTakenSlotsByPeople(intakes);

        List<AdherencePatientItemDto> out = new ArrayList<>();
        for (OrgPatientListItem patient : patients) {
            String peopleId = patient.getPeopleId();
            CarePlan plan = planByPeople.get(peopleId);
            List<CarePlanTask> peopleTasks = tasksByPeople.getOrDefault(peopleId, List.of());
            PlanWindow window = resolvePlanWindow(plan, versionById, day);
            Map<LocalDate, Map<String, String>> peopleCheckins =
                    checkinStatus.getOrDefault(peopleId, Map.of());

            boolean planRecordExists = plan != null && StringUtils.hasText(plan.getCurrentVersionId());
            // 仅「查询日仍在执行窗口内」才算有执行中方案；周期外/发布前 = 当日无方案
            boolean hasActivePlan = planRecordExists
                    && CarePlanDueSupport.isWithinHorizon(day, window.planStart(), window.horizonDays());

            DayPlanStats todayPlan = hasActivePlan
                    ? calcPlanDay(
                            peopleTasks,
                            peopleCheckins.getOrDefault(day, Map.of()),
                            day,
                            window.planStart(),
                            window.horizonDays())
                    : DayPlanStats.empty();
            int streak = hasActivePlan
                    ? calcStreak(peopleTasks, peopleCheckins, day, window.planStart(), window.horizonDays())
                    : 0;
            Double rate7d = hasActivePlan
                    ? calcRate7d(
                            peopleTasks, peopleCheckins, day, window.planStart(), window.horizonDays())
                    : null;

            List<PeopleMedication> peopleMeds = medsByPeople.getOrDefault(peopleId, List.of());
            AdherenceMedMetricsDto medMetrics =
                    calcMedMetrics(peopleMeds, takenSlotsByPeople.getOrDefault(peopleId, Map.of()), day);

            boolean hasMed = medMetrics.getActiveCount() > 0;
            if (!hasActivePlan && !hasMed) {
                continue;
            }

            AdherencePlanMetricsDto planMetrics = new AdherencePlanMetricsDto();
            planMetrics.setHasActivePlan(hasActivePlan);
            planMetrics.setDue(todayPlan.due);
            planMetrics.setDone(todayPlan.done);
            planMetrics.setSkipped(todayPlan.skipped);
            planMetrics.setIncomplete(todayPlan.incomplete);
            planMetrics.setTodayIncomplete(todayPlan.incompleteFlag);
            planMetrics.setStreakDays(streak);
            planMetrics.setRate7d(rate7d);

            AdherencePatientItemDto item = new AdherencePatientItemDto();
            item.setPeopleId(peopleId);
            item.setDisplayName(patient.getDisplayName());
            item.setCareTeamId(patient.getCareTeamId());
            item.setCareTeamName(patient.getCareTeamName());
            item.setClientLinked(patient.getClientLinked());
            item.setPlan(planMetrics);
            item.setMed(medMetrics);
            item.setRiskLevel(resolveRisk(planMetrics, medMetrics).name());
            out.add(item);
        }
        return out;
    }

    private static PlanWindow resolvePlanWindow(
            CarePlan plan, Map<String, CarePlanVersion> versionById, LocalDate fallback) {
        if (plan == null || !StringUtils.hasText(plan.getCurrentVersionId())) {
            return new PlanWindow(fallback, CarePlanDueSupport.DEFAULT_HORIZON_DAYS);
        }
        CarePlanVersion version = versionById.get(plan.getCurrentVersionId());
        LocalDate planStart = fallback;
        int horizonDays = CarePlanDueSupport.DEFAULT_HORIZON_DAYS;
        if (version != null) {
            if (version.getPublishedAt() != null) {
                planStart = version.getPublishedAt().toLocalDate();
            }
            horizonDays = CarePlanDueSupport.resolveHorizonDays(version.getExecutionJson());
        }
        return new PlanWindow(planStart, horizonDays);
    }

    private static Map<String, Map<LocalDate, Map<String, String>>> indexCheckinStatus(
            List<CarePlanTaskCheckin> checkins) {
        Map<String, Map<LocalDate, Map<String, String>>> map = new HashMap<>();
        for (CarePlanTaskCheckin c : checkins) {
            String slot = CarePlanDueSupport.resolveTimeSlot(c.getTimeSlot());
            map.computeIfAbsent(c.getPeopleId(), k -> new HashMap<>())
                    .computeIfAbsent(c.getCheckinDate(), k -> new HashMap<>())
                    .put(c.getTaskId() + "|" + slot, c.getStatus());
        }
        return map;
    }

    private static DayPlanStats calcPlanDay(
            List<CarePlanTask> tasks,
            Map<String, String> dayStatus,
            LocalDate day,
            LocalDate planStart,
            int horizonDays) {
        int due = 0;
        int done = 0;
        int skipped = 0;
        for (CarePlanTask task : tasks) {
            if (!CarePlanDueSupport.isDueOnDate(task, day, planStart, horizonDays)) {
                continue;
            }
            due++;
            String slot = CarePlanDueSupport.resolveTimeSlot(task.getTimeSlot());
            String status = dayStatus.get(task.getId() + "|" + slot);
            if (CarePlanCheckinStatusEnum.DONE.name().equals(status)) {
                done++;
            } else if (CarePlanCheckinStatusEnum.SKIPPED.name().equals(status)) {
                skipped++;
            }
        }
        int incomplete = Math.max(0, due - done - skipped);
        boolean incompleteFlag = due > 0 && (done + skipped) < due;
        return new DayPlanStats(due, done, skipped, incomplete, incompleteFlag);
    }

    private static int calcStreak(
            List<CarePlanTask> tasks,
            Map<LocalDate, Map<String, String>> checkinsByDay,
            LocalDate day,
            LocalDate planStart,
            int horizonDays) {
        int streak = 0;
        for (int i = 0; i < STREAK_LOOKBACK_DAYS; i++) {
            LocalDate d = day.minusDays(i);
            if (!CarePlanDueSupport.isWithinHorizon(d, planStart, horizonDays)) {
                // 周期外：不计入连续，也不把更早的周期内漏打延续到周期后
                if (d.isAfter(CarePlanDueSupport.resolveHorizonEnd(planStart, horizonDays))) {
                    continue;
                }
                break;
            }
            DayPlanStats stats = calcPlanDay(
                    tasks, checkinsByDay.getOrDefault(d, Map.of()), d, planStart, horizonDays);
            if (stats.due == 0) {
                continue;
            }
            if (stats.incompleteFlag) {
                streak++;
            } else {
                break;
            }
        }
        return streak;
    }

    private static Double calcRate7d(
            List<CarePlanTask> tasks,
            Map<LocalDate, Map<String, String>> checkinsByDay,
            LocalDate day,
            LocalDate planStart,
            int horizonDays) {
        int dueSum = 0;
        int doneSum = 0;
        for (int i = 0; i < RATE_WINDOW_DAYS; i++) {
            LocalDate d = day.minusDays(i);
            DayPlanStats stats = calcPlanDay(
                    tasks, checkinsByDay.getOrDefault(d, Map.of()), d, planStart, horizonDays);
            dueSum += stats.due;
            doneSum += stats.done;
        }
        if (dueSum == 0) {
            return null;
        }
        return round2((double) doneSum / (double) dueSum);
    }

    private record PlanWindow(LocalDate planStart, int horizonDays) {}

    /**
     * 用药依从：品种数保留原口径供展示，是否不依从改按频次展开的次数判定，
     * 这样 BID 只服一次会被判为未完成。
     *
     * @param takenSlotsByMed medicationId → 当日已 TAKEN 的时段集合
     */
    private static AdherenceMedMetricsDto calcMedMetrics(
            List<PeopleMedication> meds, Map<String, Set<String>> takenSlotsByMed, LocalDate day) {
        int active = 0;
        int taken = 0;
        int dueDoses = 0;
        int takenDoses = 0;
        for (PeopleMedication med : meds) {
            if (!isMedActiveOnDay(med, day)) {
                continue;
            }
            active++;
            int slotCount = takenSlotsByMed.getOrDefault(med.getId(), Set.of()).size();
            if (slotCount > 0) {
                taken++;
            }
            if (!MedicationFrequencySupport.isDueOnDay(med.getFrequency())) {
                continue;
            }
            int perDay = MedicationFrequencySupport.dosesPerDay(med.getFrequency());
            dueDoses += perDay;
            takenDoses += Math.min(slotCount, perDay);
        }
        AdherenceMedMetricsDto dto = new AdherenceMedMetricsDto();
        dto.setActiveCount(active);
        dto.setTakenCount(taken);
        dto.setDueDoseCount(dueDoses);
        dto.setTakenDoseCount(takenDoses);
        dto.setTodayIncomplete(dueDoses > 0 && takenDoses < dueDoses);
        return dto;
    }

    /** PRN 只要服过就算完成；其余按次数比对。 */
    private static String resolveMedItemStatus(boolean prn, int dueDoses, int takenDoses) {
        if (prn) {
            return takenDoses > 0 ? "TAKEN" : "PENDING";
        }
        if (takenDoses <= 0) {
            return "PENDING";
        }
        return takenDoses >= dueDoses ? "TAKEN" : "PARTIAL";
    }

    /** intake 列表 → peopleId → medicationId → 已 TAKEN 时段集合。 */
    private static Map<String, Map<String, Set<String>>> indexTakenSlotsByPeople(
            List<PeopleMedicationIntake> intakes) {
        Map<String, Map<String, Set<String>>> out = new HashMap<>();
        for (PeopleMedicationIntake intake : intakes) {
            if (!MedicationIntakeStatusEnum.TAKEN.name().equals(intake.getStatus())) {
                continue;
            }
            out.computeIfAbsent(intake.getPeopleId(), k -> new HashMap<>())
                    .computeIfAbsent(intake.getMedicationId(), k -> new HashSet<>())
                    .add(slotKey(intake.getTimeSlot()));
        }
        return out;
    }

    /** intake 列表 → medicationId → 已 TAKEN 时段集合。 */
    private static Map<String, Set<String>> indexTakenSlots(List<PeopleMedicationIntake> intakes) {
        Map<String, Set<String>> out = new HashMap<>();
        for (PeopleMedicationIntake intake : intakes) {
            if (!MedicationIntakeStatusEnum.TAKEN.name().equals(intake.getStatus())) {
                continue;
            }
            out.computeIfAbsent(intake.getMedicationId(), k -> new HashSet<>())
                    .add(slotKey(intake.getTimeSlot()));
        }
        return out;
    }

    private static String slotKey(String timeSlot) {
        return StringUtils.hasText(timeSlot) ? timeSlot : MedicationFrequencySupport.OTHER;
    }

    private static boolean isMedActiveOnDay(PeopleMedication med, LocalDate day) {
        if (med.getStartDate() != null && med.getStartDate().isAfter(day)) {
            return false;
        }
        if (med.getStopDate() != null && med.getStopDate().isBefore(day)) {
            return false;
        }
        return true;
    }

    private static AdherenceRiskLevelEnum resolveRisk(
            AdherencePlanMetricsDto plan, AdherenceMedMetricsDto med) {
        boolean high = plan.isTodayIncomplete()
                || med.isTodayIncomplete()
                || plan.getStreakDays() >= STREAK_HIGH_THRESHOLD;
        if (high) {
            return AdherenceRiskLevelEnum.HIGH;
        }
        if (plan.getRate7d() != null && plan.getRate7d() < MEDIUM_RATE_THRESHOLD) {
            return AdherenceRiskLevelEnum.MEDIUM;
        }
        return AdherenceRiskLevelEnum.LOW;
    }

    private static Comparator<AdherencePatientItemDto> patientComparator() {
        return Comparator.comparingInt((AdherencePatientItemDto i) -> riskRank(i.getRiskLevel()))
                .thenComparing(
                        (AdherencePatientItemDto i) ->
                                i.getPlan() == null ? 0 : i.getPlan().getStreakDays(),
                        Comparator.reverseOrder())
                .thenComparing(
                        (AdherencePatientItemDto i) ->
                                i.getPlan() == null ? 0 : i.getPlan().getIncomplete(),
                        Comparator.reverseOrder())
                .thenComparing(i -> Objects.toString(i.getDisplayName(), ""), String::compareTo);
    }

    private static int riskRank(String risk) {
        if (AdherenceRiskLevelEnum.HIGH.name().equals(risk)) {
            return 0;
        }
        if (AdherenceRiskLevelEnum.MEDIUM.name().equals(risk)) {
            return 1;
        }
        return 2;
    }

    private static boolean matchRisk(AdherencePatientItemDto item, AdherenceRiskLevelEnum risk) {
        if (risk == null) {
            return true;
        }
        return risk.name().equals(item.getRiskLevel());
    }

    private static boolean matchFilter(AdherencePatientItemDto item, AdherenceFilterEnum filter) {
        if (filter == null) {
            return true;
        }
        return switch (filter) {
            case FOLLOW_UP -> AdherenceRiskLevelEnum.HIGH.name().equals(item.getRiskLevel());
            case PLAN_INCOMPLETE -> item.getPlan() != null && item.getPlan().isTodayIncomplete();
            case MED_INCOMPLETE -> item.getMed() != null && item.getMed().isTodayIncomplete();
            case STREAK_GE_3 ->
                    item.getPlan() != null && item.getPlan().getStreakDays() >= STREAK_HIGH_THRESHOLD;
        };
    }

    private static AdherenceFilterEnum parseFilter(String filter) {
        if (!StringUtils.hasText(filter)) {
            return null;
        }
        try {
            return AdherenceFilterEnum.valueOf(filter.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static AdherenceRiskLevelEnum parseRisk(String risk) {
        if (!StringUtils.hasText(risk)) {
            return null;
        }
        try {
            return AdherenceRiskLevelEnum.valueOf(risk.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static Double round2(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }

    private record DayPlanStats(int due, int done, int skipped, int incomplete, boolean incompleteFlag) {
        static DayPlanStats empty() {
            return new DayPlanStats(0, 0, 0, 0, false);
        }
    }
}
