package com.healix.core.report.service;

import com.healix.common.util.JsonUtils;
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
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationFrequencySupport;
import com.healix.core.medication.support.MedicationSlotRules;
import com.healix.core.observation.domain.ExamReport;
import com.healix.core.observation.domain.LabReport;
import com.healix.core.observation.domain.LabResultItem;
import com.healix.core.observation.mapper.ExamReportMapper;
import com.healix.core.observation.mapper.LabReportMapper;
import com.healix.core.observation.mapper.LabResultItemMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.worktask.catalog.MetricFamily;
import com.healix.core.worktask.support.MetricAbnormalEvaluator;
import com.healix.core.worktask.support.MetricAbnormalEvaluator.AbnormalHit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import com.healix.core.reward.service.RewardQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 构建管理报告 content_json 快照（schemaVersion 2）。
 *
 * <p>本期统计 + 上期同口径统计（{@code prev}），概览/要点/里程碑/目标由 {@link HealthReportOverviewBuilder} 基于快照推导。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthReportContentBuilder {

    public static final int SCHEMA_VERSION = 2;

    public record BuildResult(Map<String, Object> content, int planDueCount, int medDueDayCount, int followupCount) {
        public BuildResult(Map<String, Object> content, int planDueCount, int medDueDayCount) {
            this(content, planDueCount, medDueDayCount, 0);
        }
    }

    private static final int MAX_TASK_ROWS = 12;
    private static final int MAX_ABNORMAL_ITEMS = 3;
    private static final int MAX_LAB_ITEMS = 20;
    private static final int MAX_LAB_HISTORY = 10;
    private static final int MAX_OBS_ABNORMAL = 8;

    private static final Map<String, String> TASK_CATEGORY_LABELS =
            Map.of("EXERCISE", "运动", "DIET", "饮食", "OTHER", "其他");

    private static final Map<String, String> MED_SLOT_LABELS = Map.of(
            MedicationFrequencySupport.MORNING, "早上",
            MedicationFrequencySupport.NOON, "中午",
            MedicationFrequencySupport.EVENING, "晚上",
            MedicationFrequencySupport.BEDTIME, "睡前",
            MedicationFrequencySupport.OTHER, "每日");

    private static final Map<String, String> GLUCOSE_SLOT_LABELS =
            Map.of("FASTING", "空腹", "POSTPRANDIAL", "餐后", "RANDOM", "随机");

    private final CarePlanMapper carePlanMapper;
    private final CarePlanVersionMapper versionMapper;
    private final CarePlanTaskMapper taskMapper;
    private final CarePlanTaskCheckinMapper checkinMapper;
    private final PeopleMedicationMapper medicationMapper;
    private final PeopleMedicationIntakeMapper intakeMapper;
    private final VitalRecordMapper vitalRecordMapper;
    private final FollowupRecordMapper followupRecordMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final MetricAbnormalEvaluator metricAbnormalEvaluator;
    private final LabReportMapper labReportMapper;
    private final LabResultItemMapper labResultItemMapper;
    private final ExamReportMapper examReportMapper;
    private final HealthReportLifestyleBuilder lifestyleBuilder;
    private final HealthReportOverviewBuilder overviewBuilder;
    private final ObjectProvider<RewardQueryService> rewardQueryService;

    public BuildResult build(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd, String periodType) {
        boolean weekly = !"WEEK".equals(periodType);
        LocalDate[] prevWindow = previousWindow(periodType, periodStart, periodEnd);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("type", periodType);
        period.put("start", periodStart.toString());
        period.put("end", periodEnd.toString());
        period.put("dayCount", ChronoUnit.DAYS.between(periodStart, periodEnd) + 1);
        period.put("prevStart", prevWindow[0].toString());
        period.put("prevEnd", prevWindow[1].toString());
        root.put("period", period);

        root.put("profile", buildProfile(tenantId, peopleId, periodStart, periodEnd));

        PlanAgg plan = buildPlanAdherence(tenantId, peopleId, periodStart, periodEnd, weekly);
        MedAgg med = buildMedAdherence(tenantId, peopleId, periodStart, periodEnd);
        List<Map<String, Object>> metrics = buildMetrics(tenantId, peopleId, periodStart, periodEnd);
        Map<String, Object> lifestyle =
                lifestyleBuilder.build(tenantId, peopleId, periodStart, periodEnd, true, weekly);

        PlanAgg prevPlan = buildPlanAdherence(tenantId, peopleId, prevWindow[0], prevWindow[1], false);
        MedAgg prevMed = buildMedAdherence(tenantId, peopleId, prevWindow[0], prevWindow[1]);
        List<Map<String, Object>> prevMetrics = buildMetrics(tenantId, peopleId, prevWindow[0], prevWindow[1]);
        Map<String, Object> prevLifestyle =
                lifestyleBuilder.build(tenantId, peopleId, prevWindow[0], prevWindow[1], false, false);

        Map<String, Object> planMap = plan.toMap();
        planMap.put("prev", prevPlan.prevMap());
        Map<String, Object> medMap = med.toMap();
        medMap.put("prev", prevMed.prevMap());
        attachMetricPrev(metrics, prevMetrics);
        lifestyleBuilder.attachPrev(lifestyle, prevLifestyle);

        Map<String, Object> adherence = new LinkedHashMap<>();
        adherence.put("plan", planMap);
        adherence.put("med", medMap);
        root.put("adherence", adherence);
        root.put("metrics", metrics);
        root.put("lifestyle", lifestyle);

        List<Map<String, Object>> followups = buildFollowups(tenantId, peopleId, periodStart, periodEnd);
        Map<String, Object> interventions = new LinkedHashMap<>();
        interventions.put("followups", followups);
        interventions.put("dietCommentCount", lifestyleBuilder.dietCommentCount(lifestyle));
        root.put("interventions", interventions);
        // 兼容旧字段
        root.put("followups", followups);

        boolean includeObs = "MONTH".equals(periodType) || "QUARTER".equals(periodType);
        if (includeObs) {
            root.put("observations", buildObservations(tenantId, peopleId, periodStart, periodEnd));
        }

        overviewBuilder.apply(root, periodType);

        Map<String, Object> rewards = buildRewards(tenantId, peopleId, periodStart, periodEnd);
        if (rewards != null) {
            root.put("rewards", rewards);
        }

        Map<String, Object> narrative = new LinkedHashMap<>();
        narrative.put("staffComment", null);
        narrative.put("nextFocus", null);
        narrative.put("templateTier", null);
        narrative.put("insights", null);
        narrative.put("highlights", null);
        if ("QUARTER".equals(periodType)) {
            narrative.put("quarterAdvice", null);
            narrative.put("suggestPlanAdjust", false);
        }
        root.put("narrative", narrative);

        return new BuildResult(root, plan.dueCount, med.dueDayCount, followups.size());
    }

    /** 本期成就（健康豆 / 打卡日 / 全勤 / 火苗 / 新徽章）；激励关闭或出错时不写入。 */
    private Map<String, Object> buildRewards(String tenantId, String peopleId, LocalDate start, LocalDate end) {
        RewardQueryService svc = rewardQueryService.getIfAvailable();
        if (svc == null) {
            return null;
        }
        try {
            return svc.reportSection(tenantId, peopleId, start, end);
        } catch (Exception ex) {
            log.warn("report rewards section failed people={}: {}", peopleId, ex.getMessage());
            return null;
        }
    }

    /** 兼容旧调用：默认按周报结构。 */
    public BuildResult build(String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        return build(tenantId, peopleId, periodStart, periodEnd, "WEEK");
    }

    /** 上一个同类周期：周报取前 7 天，月报/季报取上一个自然月/季。 */
    static LocalDate[] previousWindow(String periodType, LocalDate start, LocalDate end) {
        if ("MONTH".equals(periodType)) {
            LocalDate s = start.minusMonths(1).withDayOfMonth(1);
            return new LocalDate[] {s, s.plusMonths(1).minusDays(1)};
        }
        if ("QUARTER".equals(periodType)) {
            LocalDate s = start.minusMonths(3).withDayOfMonth(1);
            return new LocalDate[] {s, s.plusMonths(3).minusDays(1)};
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        return new LocalDate[] {start.minusDays(days), start.minusDays(1)};
    }

    private Map<String, Object> buildProfile(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("peopleId", peopleId);
        PeopleProfile p = peopleProfileMapper.findById(peopleId);
        if (p != null && tenantId.equals(p.getTenantId())) {
            profile.put("displayName", p.getDisplayName());
            profile.put("gender", p.getGender());
            profile.put("birthday", p.getBirthday() == null ? null : p.getBirthday().toString());
            profile.put("chronicTags", JsonUtils.readTree(p.getChronicTagsJson()));
        }
        profile.put("planVersionsInPeriod", listPlanVersionsInPeriod(tenantId, peopleId, periodStart, periodEnd));
        return profile;
    }

    private List<Map<String, Object>> listPlanVersionsInPeriod(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            return List.of();
        }
        List<CarePlanVersion> versions = publishedVersions(plan.getId());
        if (versions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDateTime periodEndExclusive = periodEnd.plusDays(1).atStartOfDay();
        for (int i = 0; i < versions.size(); i++) {
            CarePlanVersion v = versions.get(i);
            LocalDateTime from = v.getPublishedAt();
            LocalDateTime toExclusive =
                    i + 1 < versions.size() ? versions.get(i + 1).getPublishedAt() : periodEndExclusive;
            if (from == null) {
                continue;
            }
            if (from.isAfter(periodEnd.atTime(LocalTime.MAX)) || !toExclusive.isAfter(periodStart.atStartOfDay())) {
                continue;
            }
            LocalDate effectiveFrom = from.toLocalDate().isBefore(periodStart) ? periodStart : from.toLocalDate();
            LocalDate effectiveTo = toExclusive.toLocalDate().minusDays(1);
            if (effectiveTo.isAfter(periodEnd)) {
                effectiveTo = periodEnd;
            }
            if (effectiveTo.isBefore(effectiveFrom)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("versionId", v.getId());
            row.put("versionNo", v.getVersionNo());
            row.put("title", v.getTitle());
            row.put("publishedAt", from.toString());
            row.put("effectiveFrom", effectiveFrom.toString());
            row.put("effectiveTo", effectiveTo.toString());
            out.add(row);
        }
        return out;
    }

    // ---------------------------------------------------------------- 方案执行

    private PlanAgg buildPlanAdherence(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd, boolean weekly) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        List<CarePlanVersion> versions = plan == null ? List.of() : publishedVersions(plan.getId());
        Map<String, List<CarePlanTask>> tasksByVersion = new HashMap<>();
        for (CarePlanVersion v : versions) {
            List<CarePlanTask> tasks = taskMapper.listByVersionId(v.getId()).stream()
                    .filter(t -> t.getEnabled() == null || t.getEnabled() == 1)
                    .toList();
            tasksByVersion.put(v.getId(), tasks);
        }
        List<CarePlanTaskCheckin> checkins =
                checkinMapper.listByPeopleRange(tenantId, peopleId, periodStart, periodEnd);
        Map<LocalDate, Map<String, String>> checkinsByDay = indexCheckins(checkins);

        int dueSum = 0;
        int doneSum = 0;
        int skippedSum = 0;
        int fullDays = 0;
        int streak = 0;
        int longestStreak = 0;
        Map<String, TaskAgg> byTask = new LinkedHashMap<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate d = periodStart; !d.isAfter(periodEnd); d = d.plusDays(1)) {
            CarePlanVersion version = resolveVersionForDay(versions, d);
            DayPlanStats stats = DayPlanStats.empty();
            if (version != null) {
                LocalDate planStart = version.getPublishedAt().toLocalDate();
                int horizon = CarePlanDueSupport.resolveHorizonDays(version.getExecutionJson());
                stats = calcPlanDay(
                        tasksByVersion.getOrDefault(version.getId(), List.of()),
                        checkinsByDay.getOrDefault(d, Map.of()),
                        d,
                        planStart,
                        horizon,
                        byTask);
            }
            dueSum += stats.due;
            doneSum += stats.done;
            skippedSum += stats.skipped;
            if (stats.due > 0) {
                if (stats.done >= stats.due) {
                    fullDays++;
                    streak++;
                    longestStreak = Math.max(longestStreak, streak);
                } else {
                    streak = 0;
                }
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.toString());
            day.put("due", stats.due);
            day.put("done", stats.done);
            day.put("skipped", stats.skipped);
            day.put("rate", stats.due > 0 ? round2((double) stats.done / (double) stats.due) : null);
            if (version != null) {
                day.put("versionId", version.getId());
                day.put("versionNo", version.getVersionNo());
            }
            daily.add(day);
        }
        List<Map<String, Object>> taskRows = byTask.values().stream()
                .filter(t -> t.due > 0)
                .sorted(Comparator.comparingDouble(TaskAgg::rate).thenComparing(t -> -t.due))
                .limit(MAX_TASK_ROWS)
                .map(TaskAgg::toMap)
                .toList();
        List<Map<String, Object>> weeks = weekly
                ? weeklyBuckets(periodStart, periodEnd, daily, List.of("due", "done"), "done", "due")
                : null;
        return new PlanAgg(dueSum, doneSum, skippedSum, fullDays, longestStreak, daily, taskRows, weeks);
    }

    private MedAgg buildMedAdherence(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        List<PeopleMedication> meds = medicationMapper.listByPeople(tenantId, peopleId, null);
        List<PeopleMedicationIntake> intakes =
                intakeMapper.listByPeopleRange(tenantId, peopleId, periodStart, periodEnd);
        // medicationId → 日期 → 已 TAKEN 时段：按频次判定达标需要知道当天服了几次
        Map<LocalDate, Map<String, Set<String>>> takenByDay = new HashMap<>();
        for (PeopleMedicationIntake intake : intakes) {
            if (MedicationIntakeStatusEnum.TAKEN.name().equals(intake.getStatus())) {
                takenByDay
                        .computeIfAbsent(intake.getIntakeDate(), k -> new HashMap<>())
                        .computeIfAbsent(intake.getMedicationId(), k -> new HashSet<>())
                        .add(StringUtils.hasText(intake.getTimeSlot())
                                ? intake.getTimeSlot()
                                : MedicationFrequencySupport.OTHER);
            }
        }
        int dueDayCount = 0;
        int okDayCount = 0;
        int dueDoseSum = 0;
        int takenDoseSum = 0;
        Map<String, int[]> doseByDrug = new LinkedHashMap<>();
        Map<String, Integer> missedBySlot = new LinkedHashMap<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate d = periodStart; !d.isAfter(periodEnd); d = d.plusDays(1)) {
            int active = 0;
            int taken = 0;
            int dueDoses = 0;
            int takenDoses = 0;
            Map<String, Set<String>> takenSlots = takenByDay.getOrDefault(d, Map.of());
            for (PeopleMedication med : meds) {
                if (!isMedActiveOnDay(med, d)) {
                    continue;
                }
                active++;
                Set<String> slots = takenSlots.getOrDefault(med.getId(), Set.of());
                int slotCount = slots.size();
                if (slotCount > 0) {
                    taken++;
                }
                if (!MedicationFrequencySupport.isDueOnDay(med.getFrequency())) {
                    continue;
                }
                int perDay = MedicationSlotRules.dueCount(med);
                int takenToday = Math.min(slotCount, perDay);
                dueDoses += perDay;
                takenDoses += takenToday;
                int[] agg = doseByDrug.computeIfAbsent(med.getId(), k -> new int[2]);
                agg[0] += perDay;
                agg[1] += takenToday;
                int missing = perDay - takenToday;
                for (String slot : MedicationSlotRules.slotsOf(med)) {
                    if (missing <= 0) {
                        break;
                    }
                    if (!slots.contains(slot)) {
                        missedBySlot.merge(slot, 1, Integer::sum);
                        missing--;
                    }
                }
            }
            boolean ok = dueDoses > 0 && takenDoses >= dueDoses;
            if (dueDoses > 0) {
                dueDayCount++;
                if (ok) {
                    okDayCount++;
                }
            }
            dueDoseSum += dueDoses;
            takenDoseSum += takenDoses;
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.toString());
            day.put("active", active);
            day.put("taken", taken);
            day.put("dueDoses", dueDoses);
            day.put("takenDoses", takenDoses);
            day.put("ok", dueDoses == 0 ? null : ok);
            daily.add(day);
        }
        Map<String, PeopleMedication> medById = new HashMap<>();
        meds.forEach(m -> medById.put(m.getId(), m));
        List<Map<String, Object>> byDrug = new ArrayList<>();
        doseByDrug.forEach((id, agg) -> {
            PeopleMedication m = medById.get(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("medicationId", id);
            row.put("name", m == null ? null : m.getDrugName());
            row.put("frequency", m == null ? null : m.getFrequency());
            row.put("dueDoses", agg[0]);
            row.put("takenDoses", agg[1]);
            row.put("rate", agg[0] > 0 ? round2((double) agg[1] / agg[0]) : null);
            byDrug.add(row);
        });
        byDrug.sort(Comparator.comparingDouble(r -> r.get("rate") == null ? 1d : (Double) r.get("rate")));
        List<Map<String, Object>> missed = new ArrayList<>();
        missedBySlot.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("slot", e.getKey());
                    row.put("label", MED_SLOT_LABELS.getOrDefault(e.getKey(), e.getKey()));
                    row.put("count", e.getValue());
                    missed.add(row);
                });
        return new MedAgg(dueDayCount, okDayCount, dueDoseSum, takenDoseSum, daily, byDrug, missed);
    }

    // ---------------------------------------------------------------- 指标

    private List<Map<String, Object>> buildMetrics(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.plusDays(1).atStartOfDay();
        List<VitalRecord> records = vitalRecordMapper.listByPeople(tenantId, peopleId, null, from, to, 2000);
        List<VitalRecord> heights = vitalRecordMapper.listByPeople(
                tenantId, peopleId, MetricTypeEnum.HEIGHT.name(), from.minusYears(5), to, 50);
        List<AbnormalHit> hits = metricAbnormalEvaluator.evaluate(records, heights);
        Set<String> abnormalSources = new HashSet<>();
        for (AbnormalHit hit : hits) {
            abnormalSources.add(hit.sourceRecordId());
        }
        Map<MetricFamily, String> refs = metricAbnormalEvaluator.referenceTexts();

        List<Map<String, Object>> metrics = new ArrayList<>();
        addIfPresent(metrics, buildBpSeries(records, abnormalSources), MetricFamily.BP, hits, refs);
        addIfPresent(
                metrics,
                buildSimpleSeries(MetricFamily.GLUCOSE, MetricTypeEnum.BLOOD_GLUCOSE, records, abnormalSources),
                MetricFamily.GLUCOSE,
                hits,
                refs);
        addIfPresent(
                metrics,
                buildSimpleSeries(MetricFamily.HR, MetricTypeEnum.HEART_RATE, records, abnormalSources),
                MetricFamily.HR,
                hits,
                refs);
        addIfPresent(metrics, buildBmiSeries(records, heights, abnormalSources), MetricFamily.BMI, hits, refs);
        Map<String, Object> weight = buildWeightSeries(records);
        if (weight != null) {
            metrics.add(weight);
        }
        return metrics;
    }

    private static void addIfPresent(
            List<Map<String, Object>> metrics,
            Map<String, Object> envelope,
            MetricFamily family,
            List<AbnormalHit> hits,
            Map<MetricFamily, String> refs) {
        if (envelope == null) {
            return;
        }
        envelope.put("ref", refs.get(family));
        List<Map<String, Object>> items = hits.stream()
                .filter(h -> h.family() == family)
                .sorted(Comparator.comparing(
                                AbnormalHit::recordedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed())
                .limit(MAX_ABNORMAL_ITEMS)
                .map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("t", h.recordedAt() == null ? null : h.recordedAt().toString());
                    m.put("text", h.summary());
                    return m;
                })
                .toList();
        envelope.put("abnormalItems", items);
        metrics.add(envelope);
    }

    private Map<String, Object> buildBpSeries(List<VitalRecord> records, Set<String> abnormalSources) {
        Map<String, List<VitalRecord>> groups = new LinkedHashMap<>();
        List<VitalRecord> unpaired = new ArrayList<>();
        for (VitalRecord row : records) {
            if (!MetricTypeEnum.BLOOD_PRESSURE_SYS.matches(row.getMetricType())
                    && !MetricTypeEnum.BLOOD_PRESSURE_DIA.matches(row.getMetricType())) {
                continue;
            }
            if (StringUtils.hasText(row.getGroupId())) {
                groups.computeIfAbsent(row.getGroupId(), k -> new ArrayList<>()).add(row);
            } else {
                unpaired.add(row);
            }
        }
        List<Map<String, Object>> series = new ArrayList<>();
        int abnormalCount = 0;
        for (Map.Entry<String, List<VitalRecord>> e : groups.entrySet()) {
            Map<String, Object> point = bpPoint(e.getKey(), e.getValue(), abnormalSources);
            if (point != null) {
                series.add(point);
                if (Boolean.TRUE.equals(point.get("abnormal"))) {
                    abnormalCount++;
                }
            }
        }
        for (VitalRecord row : unpaired) {
            Map<String, Object> point = bpPoint(row.getId(), List.of(row), abnormalSources);
            if (point != null) {
                series.add(point);
                if (Boolean.TRUE.equals(point.get("abnormal"))) {
                    abnormalCount++;
                }
            }
        }
        if (series.isEmpty()) {
            return null;
        }
        series.sort(Comparator.comparing(m -> String.valueOf(m.get("t"))));
        Map<String, Object> env = metricEnvelope(MetricFamily.BP.name(), MetricFamily.BP.label(), series, abnormalCount);

        Map<String, Object> stats = new LinkedHashMap<>();
        Stat sys = Stat.of(series, "sys");
        Stat dia = Stat.of(series, "dia");
        stats.put("count", series.size());
        stats.put("sysAvg", sys.avgInt());
        stats.put("sysMin", sys.minInt());
        stats.put("sysMax", sys.maxInt());
        stats.put("diaAvg", dia.avgInt());
        stats.put("diaMin", dia.minInt());
        stats.put("diaMax", dia.maxInt());
        env.put("stats", stats);

        List<Map<String, Object>> byTime = new ArrayList<>();
        for (String[] slot : new String[][] {{"MORNING", "上午"}, {"EVENING", "下午/晚间"}}) {
            boolean morning = "MORNING".equals(slot[0]);
            List<Map<String, Object>> part = series.stream()
                    .filter(p -> isMorning(p.get("t")) == morning)
                    .toList();
            if (part.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot[0]);
            row.put("label", slot[1]);
            row.put("count", part.size());
            row.put("sysAvg", Stat.of(part, "sys").avgInt());
            row.put("diaAvg", Stat.of(part, "dia").avgInt());
            row.put("abnormalCount", part.stream().filter(p -> Boolean.TRUE.equals(p.get("abnormal"))).count());
            byTime.add(row);
        }
        env.put("byTime", byTime);
        return env;
    }

    private static boolean isMorning(Object t) {
        if (t == null) {
            return true;
        }
        try {
            return LocalDateTime.parse(String.valueOf(t)).getHour() < 12;
        } catch (Exception e) {
            return true;
        }
    }

    private static Map<String, Object> bpPoint(
            String sourceId, List<VitalRecord> rows, Set<String> abnormalSources) {
        BigDecimal sys = null;
        BigDecimal dia = null;
        LocalDateTime at = null;
        for (VitalRecord row : rows) {
            if (at == null || (row.getRecordedAt() != null && row.getRecordedAt().isAfter(at))) {
                at = row.getRecordedAt();
            }
            if (MetricTypeEnum.BLOOD_PRESSURE_SYS.matches(row.getMetricType())) {
                sys = row.getValue();
            } else if (MetricTypeEnum.BLOOD_PRESSURE_DIA.matches(row.getMetricType())) {
                dia = row.getValue();
            }
        }
        if (sys == null && dia == null) {
            return null;
        }
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("t", at == null ? null : at.toString());
        point.put("sys", sys);
        point.put("dia", dia);
        point.put("abnormal", abnormalSources.contains(sourceId));
        return point;
    }

    private Map<String, Object> buildSimpleSeries(
            MetricFamily family,
            MetricTypeEnum type,
            List<VitalRecord> records,
            Set<String> abnormalSources) {
        List<Map<String, Object>> series = new ArrayList<>();
        int abnormalCount = 0;
        for (VitalRecord row : records) {
            if (!type.matches(row.getMetricType()) || row.getValue() == null) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", row.getRecordedAt() == null ? null : row.getRecordedAt().toString());
            point.put("value", row.getValue());
            boolean abnormal = abnormalSources.contains(row.getId());
            point.put("abnormal", abnormal);
            if (family == MetricFamily.GLUCOSE) {
                String meal = extra(row, "mealContext");
                point.put("meal", StringUtils.hasText(meal) ? meal : "FASTING");
            }
            if (abnormal) {
                abnormalCount++;
            }
            series.add(point);
        }
        if (series.isEmpty()) {
            return null;
        }
        series.sort(Comparator.comparing(m -> String.valueOf(m.get("t"))));
        Map<String, Object> env = metricEnvelope(family.name(), family.label(), series, abnormalCount);
        env.put("stats", Stat.of(series, "value").toMap(family == MetricFamily.HR ? 0 : 1));
        if (family == MetricFamily.GLUCOSE) {
            List<Map<String, Object>> bySlot = new ArrayList<>();
            for (String slot : List.of("FASTING", "POSTPRANDIAL", "RANDOM")) {
                List<Map<String, Object>> part = series.stream()
                        .filter(p -> slot.equals(p.get("meal")))
                        .toList();
                if (part.isEmpty()) {
                    continue;
                }
                long abnormal = part.stream().filter(p -> Boolean.TRUE.equals(p.get("abnormal"))).count();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("slot", slot);
                row.put("label", GLUCOSE_SLOT_LABELS.get(slot));
                row.put("count", part.size());
                row.put("avg", Stat.of(part, "value").avg(1));
                row.put("max", Stat.of(part, "value").max());
                row.put("abnormalCount", abnormal);
                row.put("inRangeRate", round2((double) (part.size() - abnormal) / part.size()));
                bySlot.add(row);
            }
            env.put("bySlot", bySlot);
        }
        return env;
    }

    private Map<String, Object> buildBmiSeries(
            List<VitalRecord> records, List<VitalRecord> heights, Set<String> abnormalSources) {
        List<VitalRecord> heightHist = new ArrayList<>(heights == null ? List.of() : heights);
        heightHist.sort(Comparator.comparing(
                VitalRecord::getRecordedAt, Comparator.nullsLast(Comparator.naturalOrder())));
        List<Map<String, Object>> series = new ArrayList<>();
        int abnormalCount = 0;
        for (VitalRecord weight : records) {
            if (!MetricTypeEnum.WEIGHT.matches(weight.getMetricType()) || weight.getValue() == null) {
                continue;
            }
            VitalRecord height = latestHeightAt(heightHist, weight.getRecordedAt());
            if (height == null || height.getValue() == null || height.getValue().doubleValue() <= 0) {
                continue;
            }
            double hM = height.getValue().doubleValue() / 100.0d;
            if (hM <= 0) {
                continue;
            }
            BigDecimal bmi = BigDecimal.valueOf(weight.getValue().doubleValue() / (hM * hM))
                    .setScale(1, RoundingMode.HALF_UP);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", weight.getRecordedAt() == null ? null : weight.getRecordedAt().toString());
            point.put("value", bmi);
            boolean abnormal = abnormalSources.contains(weight.getId());
            point.put("abnormal", abnormal);
            if (abnormal) {
                abnormalCount++;
            }
            series.add(point);
        }
        if (series.isEmpty()) {
            return null;
        }
        series.sort(Comparator.comparing(m -> String.valueOf(m.get("t"))));
        Map<String, Object> env = metricEnvelope(MetricFamily.BMI.name(), MetricFamily.BMI.label(), series, abnormalCount);
        env.put("stats", Stat.of(series, "value").toMap(1));
        return env;
    }

    /** 体重不判异常，只看期初→期末变化。 */
    private static Map<String, Object> buildWeightSeries(List<VitalRecord> records) {
        List<Map<String, Object>> series = new ArrayList<>();
        for (VitalRecord row : records) {
            if (!MetricTypeEnum.WEIGHT.matches(row.getMetricType()) || row.getValue() == null) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("t", row.getRecordedAt() == null ? null : row.getRecordedAt().toString());
            point.put("value", row.getValue());
            point.put("abnormal", false);
            series.add(point);
        }
        if (series.isEmpty()) {
            return null;
        }
        series.sort(Comparator.comparing(m -> String.valueOf(m.get("t"))));
        Map<String, Object> env = metricEnvelope("WEIGHT", "体重", series, 0);
        env.put("inRangeRate", null);
        Map<String, Object> stats = Stat.of(series, "value").toMap(1);
        double first = num(series.get(0).get("value"));
        double last = num(series.get(series.size() - 1).get("value"));
        stats.put("first", round1(first));
        stats.put("last", round1(last));
        stats.put("delta", round1(last - first));
        stats.put("unit", "kg");
        env.put("stats", stats);
        return env;
    }

    private static VitalRecord latestHeightAt(List<VitalRecord> heights, LocalDateTime at) {
        VitalRecord best = null;
        for (VitalRecord h : heights) {
            if (h.getRecordedAt() == null) {
                continue;
            }
            if (at != null && h.getRecordedAt().isAfter(at)) {
                break;
            }
            best = h;
        }
        return best;
    }

    private static Map<String, Object> metricEnvelope(
            String family, String label, List<Map<String, Object>> series, int abnormalCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family);
        out.put("label", label);
        out.put("count", series.size());
        out.put("abnormalCount", abnormalCount);
        out.put("inRangeRate", round2((double) (series.size() - abnormalCount) / series.size()));
        out.put("latest", series.get(series.size() - 1));
        out.put("series", series);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void attachMetricPrev(List<Map<String, Object>> cur, List<Map<String, Object>> prev) {
        Map<Object, Map<String, Object>> prevByFamily = new HashMap<>();
        prev.forEach(m -> prevByFamily.put(m.get("family"), m));
        for (Map<String, Object> m : cur) {
            Map<String, Object> p = prevByFamily.get(m.get("family"));
            if (p == null) {
                m.put("prev", null);
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", p.get("count"));
            out.put("abnormalCount", p.get("abnormalCount"));
            out.put("inRangeRate", p.get("inRangeRate"));
            Map<String, Object> stats = (Map<String, Object>) p.get("stats");
            if (stats != null) {
                for (String key : List.of("avg", "sysAvg", "diaAvg", "last")) {
                    if (stats.containsKey(key)) {
                        out.put(key, stats.get(key));
                    }
                }
            }
            m.put("prev", out);
        }
    }

    // ---------------------------------------------------------------- 随访 / 检验检查

    private List<Map<String, Object>> buildFollowups(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.plusDays(1).atStartOfDay();
        List<Map<String, Object>> out = new ArrayList<>();
        for (FollowupRecord row :
                followupRecordMapper.listCompletedByPeopleRange(tenantId, peopleId, from, to)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("title", row.getTitle());
            item.put("summary", row.getSummary());
            item.put("recordType", row.getRecordType());
            item.put("completedAt", row.getCompletedAt() == null ? null : row.getCompletedAt().toString());
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> buildObservations(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        LocalDateTime from = periodStart.atStartOfDay();
        LocalDateTime to = periodEnd.plusDays(1).atStartOfDay();
        List<LabReport> allLabs = new ArrayList<>(labReportMapper.listByPeople(tenantId, peopleId));
        Function<LabReport, LocalDateTime> labTime =
                lab -> lab.getReportedAt() != null ? lab.getReportedAt() : lab.getSampledAt();
        allLabs.removeIf(lab -> labTime.apply(lab) == null);
        allLabs.sort(Comparator.comparing(labTime).reversed());

        List<LabReport> inPeriod = allLabs.stream()
                .filter(lab -> !labTime.apply(lab).isBefore(from) && labTime.apply(lab).isBefore(to))
                .toList();
        Map<String, Map<String, Object>> history = inPeriod.isEmpty()
                ? Map.of()
                : labHistory(allLabs.stream()
                        .filter(lab -> labTime.apply(lab).isBefore(from))
                        .limit(MAX_LAB_HISTORY)
                        .toList(), labTime);

        List<Map<String, Object>> labs = new ArrayList<>();
        List<Map<String, Object>> abnormalAll = new ArrayList<>();
        for (LabReport lab : inPeriod) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (LabResultItem it : labResultItemMapper.listByReportId(lab.getId())) {
                Map<String, Object> row = labItemRow(it);
                Map<String, Object> prev = history.get(itemKey(it));
                if (prev != null) {
                    row.put("prevValue", prev.get("value"));
                    row.put("prevDate", prev.get("date"));
                }
                items.add(row);
            }
            items.sort(Comparator.comparing(r -> isAbnormalFlag(r.get("flag")) ? 0 : 1));
            long abnormal = items.stream().filter(r -> isAbnormalFlag(r.get("flag"))).count();
            items.stream().filter(r -> isAbnormalFlag(r.get("flag"))).forEach(abnormalAll::add);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", lab.getId());
            item.put("specimenType", lab.getSpecimenType());
            item.put("sampledAt", lab.getSampledAt() == null ? null : lab.getSampledAt().toString());
            item.put("reportedAt", lab.getReportedAt() == null ? null : lab.getReportedAt().toString());
            item.put("note", lab.getNote());
            item.put("itemCount", items.size());
            item.put("abnormalCount", abnormal);
            item.put("items", items.size() > MAX_LAB_ITEMS ? items.subList(0, MAX_LAB_ITEMS) : items);
            labs.add(item);
        }
        List<Map<String, Object>> exams = new ArrayList<>();
        for (ExamReport exam : examReportMapper.listByPeople(tenantId, peopleId, null)) {
            LocalDateTime t = exam.getExaminedAt() != null ? exam.getExaminedAt() : exam.getGmtCreated();
            if (t == null || t.isBefore(from) || !t.isBefore(to)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", exam.getId());
            item.put("examType", exam.getExamType());
            item.put("examinedAt", exam.getExaminedAt() == null ? null : exam.getExaminedAt().toString());
            item.put("conclusion", exam.getConclusion());
            exams.add(item);
        }
        Map<String, Object> obs = new LinkedHashMap<>();
        obs.put("labs", labs);
        obs.put("exams", exams);
        obs.put("labCount", labs.size());
        obs.put("examCount", exams.size());
        obs.put("abnormalItems", abnormalAll.size() > MAX_OBS_ABNORMAL
                ? abnormalAll.subList(0, MAX_OBS_ABNORMAL)
                : abnormalAll);
        return obs;
    }

    /** 本期之前最近的同项目结果：itemKey → {value, date}；labs 已按时间倒序。 */
    private Map<String, Map<String, Object>> labHistory(
            List<LabReport> earlierLabs, Function<LabReport, LocalDateTime> labTime) {
        Map<String, Map<String, Object>> out = new HashMap<>();
        for (LabReport lab : earlierLabs) {
            String date = labTime.apply(lab).toLocalDate().toString();
            for (LabResultItem it : labResultItemMapper.listByReportId(lab.getId())) {
                String key = itemKey(it);
                if (key == null || out.containsKey(key)) {
                    continue;
                }
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", it.getValueNum() != null ? it.getValueNum() : it.getValueText());
                v.put("date", date);
                out.put(key, v);
            }
        }
        return out;
    }

    private static Map<String, Object> labItemRow(LabResultItem it) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", it.getItemCode());
        row.put("name", StringUtils.hasText(it.getItemName()) ? it.getItemName() : it.getItemCode());
        row.put("value", it.getValueNum() != null ? it.getValueNum() : it.getValueText());
        row.put("unit", it.getUnit());
        row.put("ref", refText(it.getRefLow(), it.getRefHigh()));
        row.put("flag", StringUtils.hasText(it.getAbnormalFlag()) ? it.getAbnormalFlag() : null);
        return row;
    }

    private static String itemKey(LabResultItem it) {
        if (StringUtils.hasText(it.getItemCode())) {
            return it.getItemCode();
        }
        return StringUtils.hasText(it.getItemName()) ? "name:" + it.getItemName() : null;
    }

    private static boolean isAbnormalFlag(Object flag) {
        if (flag == null) {
            return false;
        }
        String f = String.valueOf(flag).trim().toUpperCase();
        return !f.isEmpty() && !"N".equals(f) && !"NORMAL".equals(f);
    }

    private static String refText(BigDecimal low, BigDecimal high) {
        if (low != null && high != null) {
            return low.stripTrailingZeros().toPlainString() + "~" + high.stripTrailingZeros().toPlainString();
        }
        if (low != null) {
            return "≥" + low.stripTrailingZeros().toPlainString();
        }
        if (high != null) {
            return "≤" + high.stripTrailingZeros().toPlainString();
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    /** 按 7 天切块汇总 daily 的若干字段，并按 num/den 算比率。 */
    static List<Map<String, Object>> weeklyBuckets(
            LocalDate start,
            LocalDate end,
            List<Map<String, Object>> daily,
            List<String> sumKeys,
            String rateNum,
            String rateDen) {
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (LocalDate ws = start; !ws.isAfter(end); ws = ws.plusDays(7)) {
            LocalDate we = ws.plusDays(6).isAfter(end) ? end : ws.plusDays(6);
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("weekStart", ws.toString());
            w.put("weekEnd", we.toString());
            for (String k : sumKeys) {
                w.put(k, 0d);
            }
            weeks.add(w);
        }
        for (Map<String, Object> day : daily) {
            LocalDate d = LocalDate.parse(String.valueOf(day.get("date")));
            int idx = (int) (ChronoUnit.DAYS.between(start, d) / 7);
            if (idx < 0 || idx >= weeks.size()) {
                continue;
            }
            Map<String, Object> w = weeks.get(idx);
            for (String k : sumKeys) {
                w.put(k, num(w.get(k)) + num(day.get(k)));
            }
        }
        for (Map<String, Object> w : weeks) {
            for (String k : sumKeys) {
                w.put(k, Math.round(num(w.get(k))));
            }
            if (rateNum != null) {
                double den = num(w.get(rateDen));
                w.put("rate", den > 0 ? round2(num(w.get(rateNum)) / den) : null);
            }
        }
        return weeks;
    }

    private List<CarePlanVersion> publishedVersions(String planId) {
        return versionMapper.listByPlanId(planId).stream()
                .filter(v -> v.getPublishedAt() != null)
                .sorted(Comparator.comparing(CarePlanVersion::getPublishedAt)
                        .thenComparing(CarePlanVersion::getVersionNo, Comparator.nullsLast(Integer::compareTo)))
                .toList();
    }

    /** 取 published_at ≤ 当日结束 的最新版本。 */
    private static CarePlanVersion resolveVersionForDay(List<CarePlanVersion> versions, LocalDate day) {
        LocalDateTime endOfDay = day.atTime(LocalTime.MAX);
        CarePlanVersion best = null;
        for (CarePlanVersion v : versions) {
            if (v.getPublishedAt() == null || v.getPublishedAt().isAfter(endOfDay)) {
                continue;
            }
            best = v;
        }
        return best;
    }

    private static Map<LocalDate, Map<String, String>> indexCheckins(List<CarePlanTaskCheckin> checkins) {
        Map<LocalDate, Map<String, String>> map = new HashMap<>();
        for (CarePlanTaskCheckin c : checkins) {
            String slot = CarePlanDueSupport.resolveTimeSlot(c.getTimeSlot());
            map.computeIfAbsent(c.getCheckinDate(), k -> new HashMap<>())
                    .put(c.getTaskId() + "|" + slot, c.getStatus());
        }
        return map;
    }

    private static DayPlanStats calcPlanDay(
            List<CarePlanTask> tasks,
            Map<String, String> dayStatus,
            LocalDate day,
            LocalDate planStart,
            int horizonDays,
            Map<String, TaskAgg> byTask) {
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
            // 跨版本同一任务按 taskCode/标题+时段归并
            String key = (StringUtils.hasText(task.getTaskCode()) ? task.getTaskCode() : task.getTitle()) + "|" + slot;
            TaskAgg agg = byTask.computeIfAbsent(key, k -> new TaskAgg(task.getTitle(), task.getCategory(), slot));
            agg.due++;
            if (CarePlanCheckinStatusEnum.DONE.name().equals(status)) {
                done++;
                agg.done++;
            } else if (CarePlanCheckinStatusEnum.SKIPPED.name().equals(status)) {
                skipped++;
                agg.skipped++;
            }
        }
        return new DayPlanStats(due, done, skipped);
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

    private static String extra(VitalRecord row, String key) {
        if (row == null || !StringUtils.hasText(row.getExtraJson())) {
            return null;
        }
        var n = JsonUtils.readTree(row.getExtraJson()).get(key);
        return n == null || n.isNull() ? null : n.asText();
    }

    static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return 0d;
    }

    static Double round2(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }

    static Double round1(double v) {
        return Math.round(v * 10.0d) / 10.0d;
    }

    /** 序列某字段的均值/极值。 */
    private record Stat(int n, double sum, Double min, Double max) {
        static Stat of(List<Map<String, Object>> series, String key) {
            int n = 0;
            double sum = 0;
            Double min = null;
            Double max = null;
            for (Map<String, Object> p : series) {
                if (!(p.get(key) instanceof Number v)) {
                    continue;
                }
                double d = v.doubleValue();
                n++;
                sum += d;
                min = min == null ? d : Math.min(min, d);
                max = max == null ? d : Math.max(max, d);
            }
            return new Stat(n, sum, min, max);
        }

        Double avg(int scale) {
            if (n == 0) {
                return null;
            }
            return BigDecimal.valueOf(sum / n).setScale(scale, RoundingMode.HALF_UP).doubleValue();
        }

        Long avgInt() {
            return n == 0 ? null : Math.round(sum / n);
        }

        Long minInt() {
            return min == null ? null : Math.round(min);
        }

        Long maxInt() {
            return max == null ? null : Math.round(max);
        }

        Map<String, Object> toMap(int scale) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("count", n);
            m.put("avg", avg(scale));
            m.put("min", min);
            m.put("max", max);
            return m;
        }
    }

    private record DayPlanStats(int due, int done, int skipped) {
        static DayPlanStats empty() {
            return new DayPlanStats(0, 0, 0);
        }
    }

    private static final class TaskAgg {
        final String title;
        final String category;
        final String timeSlot;
        int due;
        int done;
        int skipped;

        TaskAgg(String title, String category, String timeSlot) {
            this.title = title;
            this.category = category;
            this.timeSlot = timeSlot;
        }

        double rate() {
            return due > 0 ? (double) done / due : 1d;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title);
            m.put("category", category);
            m.put("categoryLabel", TASK_CATEGORY_LABELS.getOrDefault(String.valueOf(category), "其他"));
            m.put("timeSlot", timeSlot);
            m.put("due", due);
            m.put("done", done);
            m.put("skipped", skipped);
            m.put("rate", round2(rate()));
            return m;
        }
    }

    private record PlanAgg(
            int dueCount,
            int doneCount,
            int skippedCount,
            int fullDays,
            int longestStreak,
            List<Map<String, Object>> daily,
            List<Map<String, Object>> byTask,
            List<Map<String, Object>> weekly) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dueCount", dueCount);
            m.put("doneCount", doneCount);
            m.put("skippedCount", skippedCount);
            m.put("rate", dueCount > 0 ? round2((double) doneCount / (double) dueCount) : null);
            m.put("fullDays", fullDays);
            m.put("longestStreak", longestStreak);
            m.put("byTask", byTask);
            if (weekly != null) {
                m.put("weekly", weekly);
            }
            m.put("daily", daily);
            return m;
        }

        Map<String, Object> prevMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dueCount", dueCount);
            m.put("doneCount", doneCount);
            m.put("rate", dueCount > 0 ? round2((double) doneCount / (double) dueCount) : null);
            return m;
        }
    }

    private record MedAgg(
            int dueDayCount,
            int okDayCount,
            int dueDoseCount,
            int takenDoseCount,
            List<Map<String, Object>> daily,
            List<Map<String, Object>> byDrug,
            List<Map<String, Object>> missedBySlot) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dueDayCount", dueDayCount);
            m.put("okDayCount", okDayCount);
            m.put("rate", dueDayCount > 0 ? round2((double) okDayCount / (double) dueDayCount) : null);
            m.put("dueDoseCount", dueDoseCount);
            m.put("takenDoseCount", takenDoseCount);
            m.put("doseRate", dueDoseCount > 0 ? round2((double) takenDoseCount / (double) dueDoseCount) : null);
            m.put("byDrug", byDrug);
            m.put("missedBySlot", missedBySlot);
            m.put("daily", daily);
            return m;
        }

        Map<String, Object> prevMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dueDayCount", dueDayCount);
            m.put("rate", dueDayCount > 0 ? round2((double) okDayCount / (double) dueDayCount) : null);
            m.put("doseRate", dueDoseCount > 0 ? round2((double) takenDoseCount / (double) dueDoseCount) : null);
            return m;
        }
    }
}
