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
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.observation.domain.ExamReport;
import com.healix.core.observation.domain.LabReport;
import com.healix.core.observation.mapper.ExamReportMapper;
import com.healix.core.observation.mapper.LabReportMapper;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 构建管理报告 content_json 快照（按日切片方案 due + 用药日 + 指标 + 随访）。 */
@Service
@RequiredArgsConstructor
public class HealthReportContentBuilder {

    public record BuildResult(Map<String, Object> content, int planDueCount, int medDueDayCount, int followupCount) {
        public BuildResult(Map<String, Object> content, int planDueCount, int medDueDayCount) {
            this(content, planDueCount, medDueDayCount, 0);
        }
    }

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
    private final ExamReportMapper examReportMapper;

    public BuildResult build(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd, String periodType) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("type", periodType);
        period.put("start", periodStart.toString());
        period.put("end", periodEnd.toString());
        root.put("period", period);

        root.put("profile", buildProfile(tenantId, peopleId, periodStart, periodEnd));

        PlanAgg plan = buildPlanAdherence(tenantId, peopleId, periodStart, periodEnd);
        MedAgg med = buildMedAdherence(tenantId, peopleId, periodStart, periodEnd);

        Map<String, Object> adherence = new LinkedHashMap<>();
        adherence.put("plan", plan.toMap());
        adherence.put("med", med.toMap());
        root.put("adherence", adherence);

        root.put("metrics", buildMetrics(tenantId, peopleId, periodStart, periodEnd));

        List<Map<String, Object>> followups = buildFollowups(tenantId, peopleId, periodStart, periodEnd);
        Map<String, Object> interventions = new LinkedHashMap<>();
        interventions.put("followups", followups);
        root.put("interventions", interventions);
        // 兼容旧字段
        root.put("followups", followups);

        boolean includeObs = "MONTH".equals(periodType) || "QUARTER".equals(periodType);
        if (includeObs) {
            root.put("observations", buildObservations(tenantId, peopleId, periodStart, periodEnd));
        }

        Map<String, Object> narrative = new LinkedHashMap<>();
        narrative.put("staffComment", null);
        narrative.put("nextFocus", null);
        narrative.put("templateTier", null);
        if ("QUARTER".equals(periodType)) {
            narrative.put("quarterAdvice", null);
            narrative.put("suggestPlanAdjust", false);
        }
        root.put("narrative", narrative);

        return new BuildResult(root, plan.dueCount, med.dueDayCount, followups.size());
    }

    /** 兼容旧调用：默认按周报结构。 */
    public BuildResult build(String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
        return build(tenantId, peopleId, periodStart, periodEnd, "WEEK");
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

    private PlanAgg buildPlanAdherence(
            String tenantId, String peopleId, LocalDate periodStart, LocalDate periodEnd) {
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
                        horizon);
            }
            dueSum += stats.due;
            doneSum += stats.done;
            skippedSum += stats.skipped;
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
        return new PlanAgg(dueSum, doneSum, skippedSum, daily);
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
                int slotCount = takenSlots.getOrDefault(med.getId(), Set.of()).size();
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
        return new MedAgg(dueDayCount, okDayCount, dueDoseSum, takenDoseSum, daily);
    }

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

        List<Map<String, Object>> metrics = new ArrayList<>();
        Map<String, Object> bp = buildBpSeries(records, abnormalSources);
        if (bp != null) {
            metrics.add(bp);
        }
        Map<String, Object> glucose = buildSimpleSeries(
                MetricFamily.GLUCOSE, MetricTypeEnum.BLOOD_GLUCOSE, records, abnormalSources);
        if (glucose != null) {
            metrics.add(glucose);
        }
        Map<String, Object> hr =
                buildSimpleSeries(MetricFamily.HR, MetricTypeEnum.HEART_RATE, records, abnormalSources);
        if (hr != null) {
            metrics.add(hr);
        }
        Map<String, Object> bmi = buildBmiSeries(records, heights, abnormalSources);
        if (bmi != null) {
            metrics.add(bmi);
        }
        return metrics;
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
        return metricEnvelope(MetricFamily.BP, series, abnormalCount);
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
            if (abnormal) {
                abnormalCount++;
            }
            series.add(point);
        }
        if (series.isEmpty()) {
            return null;
        }
        series.sort(Comparator.comparing(m -> String.valueOf(m.get("t"))));
        return metricEnvelope(family, series, abnormalCount);
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
        return metricEnvelope(MetricFamily.BMI, series, abnormalCount);
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
            MetricFamily family, List<Map<String, Object>> series, int abnormalCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family.name());
        out.put("label", family.label());
        out.put("abnormalCount", abnormalCount);
        out.put("latest", series.get(series.size() - 1));
        out.put("series", series);
        return out;
    }

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
        List<Map<String, Object>> labs = new ArrayList<>();
        for (LabReport lab : labReportMapper.listByPeople(tenantId, peopleId)) {
            LocalDateTime t = lab.getReportedAt() != null ? lab.getReportedAt() : lab.getSampledAt();
            if (t == null || t.isBefore(from) || !t.isBefore(to)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", lab.getId());
            item.put("specimenType", lab.getSpecimenType());
            item.put("sampledAt", lab.getSampledAt() == null ? null : lab.getSampledAt().toString());
            item.put("reportedAt", lab.getReportedAt() == null ? null : lab.getReportedAt().toString());
            item.put("note", lab.getNote());
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
        return obs;
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

    private static Double round2(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }

    private record DayPlanStats(int due, int done, int skipped) {
        static DayPlanStats empty() {
            return new DayPlanStats(0, 0, 0);
        }
    }

    private record PlanAgg(int dueCount, int doneCount, int skippedCount, List<Map<String, Object>> daily) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dueCount", dueCount);
            m.put("doneCount", doneCount);
            m.put("skippedCount", skippedCount);
            m.put("rate", dueCount > 0 ? round2((double) doneCount / (double) dueCount) : null);
            m.put("daily", daily);
            return m;
        }
    }

    private record MedAgg(
            int dueDayCount,
            int okDayCount,
            int dueDoseCount,
            int takenDoseCount,
            List<Map<String, Object>> daily) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dueDayCount", dueDayCount);
            m.put("okDayCount", okDayCount);
            m.put("rate", dueDayCount > 0 ? round2((double) okDayCount / (double) dueDayCount) : null);
            m.put("dueDoseCount", dueDoseCount);
            m.put("takenDoseCount", takenDoseCount);
            m.put("doseRate", dueDoseCount > 0 ? round2((double) takenDoseCount / (double) dueDoseCount) : null);
            m.put("daily", daily);
            return m;
        }
    }
}
