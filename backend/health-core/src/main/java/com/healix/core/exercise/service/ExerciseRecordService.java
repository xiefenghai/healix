package com.healix.core.exercise.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanTodayTaskDto;
import com.healix.core.careplan.enums.CarePlanCheckinStatusEnum;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.exercise.domain.ExerciseSession;
import com.healix.core.exercise.dto.ExerciseFlagDto;
import com.healix.core.exercise.dto.ExerciseSessionDto;
import com.healix.core.exercise.dto.ExerciseTodayDto;
import com.healix.core.exercise.dto.ExerciseTypeDto;
import com.healix.core.exercise.dto.ExerciseWeekDto;
import com.healix.core.exercise.enums.ExerciseCategoryEnum;
import com.healix.core.exercise.enums.ExerciseSourceEnum;
import com.healix.core.exercise.enums.ExerciseSymptomEnum;
import com.healix.core.exercise.enums.IntensityEnum;
import com.healix.core.exercise.mapper.ExerciseSessionMapper;
import com.healix.core.exercise.support.ActivityCalculator;
import com.healix.core.exercise.support.ActivityCalculator.Activity;
import com.healix.core.exercise.support.ExerciseCatalog;
import com.healix.core.exercise.support.ExercisePlanLoader;
import com.healix.core.exercise.support.ExercisePlanLoader.ExercisePlan;
import com.healix.core.exercise.support.ExercisePlanLoader.TaskTarget;
import com.healix.core.exercise.support.ExerciseRuleEngine;
import com.healix.core.exercise.support.ExerciseTaskTargetResolver.ExerciseTarget;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/** 运动记录：增删改查、活跃分钟/热量快照、规则提示、EXERCISE 任务自动打卡、RED 症状开工作台任务。 */
@Slf4j
@Service
public class ExerciseRecordService {

    private static final int BACKFILL_DAYS = 7;
    private static final int MAX_DURATION_MIN = 600;
    private static final int MAX_RANGE_DAYS = 92;
    private static final int MAX_NOTE = 200;
    /** 客户端与服务器时钟偏差容忍 */
    private static final int FUTURE_TOLERANCE_MIN = 5;

    private final ExerciseSessionMapper sessionMapper;
    private final ExerciseCatalog catalog;
    private final ExercisePlanLoader planLoader;
    private final ActivityCalculator calculator;
    private final ExerciseRuleEngine ruleEngine;
    private final CarePlanCheckinService checkinService;
    private final VitalRecordMapper vitalRecordMapper;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;
    private final TransactionTemplate requiresNew;

    public ExerciseRecordService(
            ExerciseSessionMapper sessionMapper,
            ExerciseCatalog catalog,
            ExercisePlanLoader planLoader,
            ActivityCalculator calculator,
            ExerciseRuleEngine ruleEngine,
            CarePlanCheckinService checkinService,
            VitalRecordMapper vitalRecordMapper,
            WorkspaceTaskGenerator workspaceTaskGenerator,
            PlatformTransactionManager transactionManager) {
        this.sessionMapper = sessionMapper;
        this.catalog = catalog;
        this.planLoader = planLoader;
        this.calculator = calculator;
        this.ruleEngine = ruleEngine;
        this.checkinService = checkinService;
        this.vitalRecordMapper = vitalRecordMapper;
        this.workspaceTaskGenerator = workspaceTaskGenerator;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record SessionInput(
            String typeCode,
            LocalDateTime startedAt,
            Integer durationMin,
            String intensity,
            Integer avgHr,
            Integer distanceM,
            List<String> symptoms,
            String note,
            String source,
            String carePlanTaskId) {}

    /** 录入人：患者本人或医护代录（二选一）。 */
    public record Actor(String peopleId, String staffId) {
        public static Actor patient(String peopleId) {
            return new Actor(peopleId, null);
        }

        public static Actor staff(String staffId) {
            return new Actor(null, staffId);
        }

        boolean isPatient() {
            return staffId == null;
        }
    }

    // ------------------------------------------------------------------ 写

    @Transactional
    public ExerciseSessionDto create(String tenantId, String peopleId, SessionInput input, Actor actor) {
        ExerciseSession s = new ExerciseSession();
        s.setTenantId(tenantId);
        s.setPeopleId(peopleId);
        s.setRecordedByPeopleId(actor.peopleId());
        s.setRecordedByStaffId(actor.staffId());
        EntityMeta.onCreate(s);
        List<ExerciseSymptomEnum> symptoms = fill(s, input, actor);
        sessionMapper.insert(s);
        if (ExerciseRuleEngine.RED.equals(s.getSymptomLevel())) {
            raiseAlert(s, symptoms);
        }
        syncCheckinsAfterCommit(tenantId, peopleId, s.getExerciseDate());
        return toDto(s, actor.isPatient());
    }

    @Transactional
    public ExerciseSessionDto update(String tenantId, String peopleId, String id, SessionInput input, Actor actor) {
        ExerciseSession s = requireSession(tenantId, peopleId, id);
        assertWritable(s, actor);
        LocalDate oldDate = s.getExerciseDate();
        boolean wasRed = ExerciseRuleEngine.RED.equals(s.getSymptomLevel());
        List<ExerciseSymptomEnum> symptoms = fill(s, input, actor);
        EntityMeta.onUpdate(s);
        sessionMapper.update(s);
        if (!wasRed && ExerciseRuleEngine.RED.equals(s.getSymptomLevel())) {
            raiseAlert(s, symptoms);
        }
        syncCheckinsAfterCommit(tenantId, peopleId, s.getExerciseDate(), oldDate);
        return toDto(s, actor.isPatient());
    }

    @Transactional
    public void delete(String tenantId, String peopleId, String id, Actor actor) {
        ExerciseSession s = requireSession(tenantId, peopleId, id);
        assertWritable(s, actor);
        sessionMapper.softDelete(s.getId());
        if (StringUtils.hasText(s.getAlertTaskId())) {
            workspaceTaskGenerator.onExerciseSessionDeleted(s.getAlertTaskId(), s.getId());
        }
        syncCheckinsAfterCommit(tenantId, peopleId, s.getExerciseDate());
    }

    // ------------------------------------------------------------------ 读

    public ExerciseSessionDto get(String tenantId, String peopleId, String id, boolean patientView) {
        return toDto(requireSession(tenantId, peopleId, id), patientView);
    }

    public List<ExerciseSessionDto> list(
            String tenantId, String peopleId, LocalDate from, LocalDate to, boolean patientView) {
        LocalDate end = to != null ? to : today();
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BusinessException(400, "查询区间不超过 " + MAX_RANGE_DAYS + " 天");
        }
        return sessionMapper.listByPeopleRange(tenantId, peopleId, start, end).stream()
                .map(s -> toDto(s, patientView))
                .toList();
    }

    public ExerciseTodayDto today(String tenantId, String peopleId, boolean patientView) {
        LocalDate day = today();
        ExercisePlan plan = planLoader.load(tenantId, peopleId, day);
        List<ExerciseSession> sessions = sessionMapper.listByPeopleRange(tenantId, peopleId, day, day);
        Map<String, String> statusByTask = new HashMap<>();
        for (CarePlanTodayTaskDto t : checkinService.getPatientToday(tenantId, peopleId, day).getTasks()) {
            statusByTask.put(t.getId(), t.getCheckinStatus());
        }

        List<ExerciseTodayDto.TaskItem> items = new ArrayList<>();
        for (TaskTarget tt : plan.dueOn(day)) {
            ExerciseTarget target = tt.target();
            ExerciseTodayDto.TaskItem item = new ExerciseTodayDto.TaskItem();
            item.setTaskId(target.taskId());
            item.setTaskCode(target.taskCode());
            item.setTitle(target.title());
            item.setTypeCode(target.typeCode());
            item.setTypeName(catalog.find(target.typeCode()).map(ExerciseTypeDto::name).orElse(null));
            item.setCategory(target.category().name());
            item.setTargetMin(target.targetMin());
            item.setIntensity(target.intensity().name());
            item.setDoneMin(minutesOf(sessions, target.category()));
            item.setCheckinStatus(statusByTask.get(target.taskId()));
            items.add(item);
        }

        ExerciseTodayDto out = new ExerciseTodayDto();
        out.setDate(day);
        out.setPlanState(plan.state().name());
        out.setTasks(items);
        out.setContraindications(plan.contraindications());
        out.setPrecautions(plan.precautions());
        out.setSessions(sessions.stream().map(s -> toDto(s, patientView)).toList());
        return out;
    }

    public ExerciseWeekDto week(String tenantId, String peopleId, LocalDate anyDay) {
        LocalDate today = today();
        LocalDate ref = anyDay != null ? anyDay : today;
        LocalDate start = ref.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate end = start.plusDays(6);
        ExercisePlan plan = planLoader.load(tenantId, peopleId, today);

        Map<LocalDate, List<ExerciseSession>> byDay = new HashMap<>();
        for (ExerciseSession s : sessionMapper.listByPeopleRange(tenantId, peopleId, start, end)) {
            byDay.computeIfAbsent(s.getExerciseDate(), k -> new ArrayList<>()).add(s);
        }
        Map<LocalDate, Integer> steps = dailySteps(tenantId, peopleId, start, end);

        ExerciseWeekDto out = new ExerciseWeekDto();
        out.setWeekStart(start);
        out.setWeekEnd(end);
        out.setPlanState(plan.state().name());
        out.setWeeklyActiveMinTarget(plan.weeklyActiveMin());
        out.setHasSteps(!steps.isEmpty());
        List<ExerciseWeekDto.Day> days = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            List<ExerciseSession> ss = byDay.getOrDefault(d, List.of());
            ExerciseWeekDto.Day day = new ExerciseWeekDto.Day();
            day.setDate(d);
            day.setActiveMin(ss.stream().mapToInt(s -> nz(s.getActiveMin())).sum());
            day.setTotalMin(ss.stream().mapToInt(s -> nz(s.getDurationMin())).sum());
            day.setSteps(steps.get(d));
            day.setLevel(ss.isEmpty() ? null : worstLevel(ss));
            if (!d.isAfter(today)) {
                for (CarePlanTodayTaskDto t : checkinService.getPatientToday(tenantId, peopleId, d).getTasks()) {
                    if (!ExercisePlanLoader.CATEGORY_EXERCISE.equals(t.getCategory())) {
                        continue;
                    }
                    day.setDueCount(day.getDueCount() + 1);
                    if (CarePlanCheckinStatusEnum.DONE.name().equals(t.getCheckinStatus())) {
                        day.setDoneCount(day.getDoneCount() + 1);
                    }
                }
            }
            days.add(day);
            out.setDueCount(out.getDueCount() + day.getDueCount());
            out.setDoneCount(out.getDoneCount() + day.getDoneCount());
            out.setActiveMin(out.getActiveMin() + day.getActiveMin());
            out.setTotalMin(out.getTotalMin() + day.getTotalMin());
            out.setSessionCount(out.getSessionCount() + ss.size());
        }
        out.setDays(days);
        return out;
    }

    public List<ExerciseTypeDto> frequentTypes(String tenantId, String peopleId, int limit) {
        int cap = Math.max(1, Math.min(limit, 12));
        List<ExerciseTypeDto> out = new ArrayList<>();
        for (String code : sessionMapper.listFrequentTypeCodes(tenantId, peopleId, today().minusDays(60), cap)) {
            catalog.find(code).ifPresent(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ 计算

    private List<ExerciseSymptomEnum> fill(ExerciseSession s, SessionInput input, Actor actor) {
        if (input == null) {
            throw new BusinessException(400, "请求不能为空");
        }
        ExerciseTypeDto type = catalog.require(input.typeCode());
        int duration = input.durationMin() == null ? 0 : input.durationMin();
        if (duration < 1 || duration > MAX_DURATION_MIN) {
            throw new BusinessException(400, "运动时长需在 1–" + MAX_DURATION_MIN + " 分钟之间");
        }
        LocalDateTime startedAt = input.startedAt();
        if (startedAt == null) {
            throw new BusinessException(400, "请填写开始时间");
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        if (startedAt.isAfter(now.plusMinutes(FUTURE_TOLERANCE_MIN))) {
            throw new BusinessException(400, "不能记录未来时间的运动");
        }
        LocalDate date = startedAt.toLocalDate();
        if (date.isBefore(now.toLocalDate().minusDays(BACKFILL_DAYS - 1L))) {
            throw new BusinessException(400, "仅支持补记最近 " + BACKFILL_DAYS + " 天");
        }
        if (input.avgHr() != null && (input.avgHr() < 30 || input.avgHr() > 220)) {
            throw new BusinessException(400, "平均心率需在 30–220 之间");
        }
        if (input.distanceM() != null && (input.distanceM() < 0 || input.distanceM() > 100_000)) {
            throw new BusinessException(400, "距离需在 0–100 公里之间");
        }
        String note = StringUtils.hasText(input.note()) ? input.note().trim() : null;
        if (note != null && note.length() > MAX_NOTE) {
            throw new BusinessException(400, "备注不超过 " + MAX_NOTE + " 字");
        }

        ExercisePlan plan = planLoader.load(s.getTenantId(), s.getPeopleId(), now.toLocalDate());
        ExerciseCategoryEnum category = ExerciseCatalog.categoryOf(type);
        List<TaskTarget> due = plan.dueOn(date);
        ExerciseTarget matched = due.stream()
                .map(TaskTarget::target)
                .filter(t -> t.category() == category)
                .findFirst()
                .orElse(null);
        IntensityEnum intensity = IntensityEnum.parseOr(
                input.intensity(),
                matched != null ? matched.intensity() : IntensityEnum.parseOr(type.defaultIntensity(), IntensityEnum.MODERATE));
        List<ExerciseSymptomEnum> symptoms = ExerciseSymptomEnum.parseAll(input.symptoms());

        Activity activity = calculator.compute(
                type, intensity, duration, calculator.latestWeightKg(s.getTenantId(), s.getPeopleId()));
        ExerciseRuleEngine.Result rules = ruleEngine.evaluate(type, intensity, duration, symptoms, plan, matched);
        List<ExerciseFlagDto> flags = new ArrayList<>(rules.flags());
        List<ExerciseRuleEngine.TimeSlot> others = sessionMapper
                .listByPeopleRange(s.getTenantId(), s.getPeopleId(), date.minusDays(1), date)
                .stream()
                .filter(o -> !Objects.equals(o.getId(), s.getId()))
                .map(o -> new ExerciseRuleEngine.TimeSlot(o.getStartedAt(), nz(o.getDurationMin()), o.getTypeName()))
                .toList();
        ExerciseFlagDto overlap = ExerciseRuleEngine.overlapFlag(startedAt, duration, others);
        if (overlap != null) {
            flags.add(overlap);
        }

        String planTaskId = StringUtils.hasText(input.carePlanTaskId())
                        && due.stream().anyMatch(t -> input.carePlanTaskId().equals(t.task().getId()))
                ? input.carePlanTaskId()
                : null;
        ExerciseSourceEnum source = actor.isPatient()
                ? ExerciseSourceEnum.parsePatient(input.source())
                : ExerciseSourceEnum.STAFF;

        s.setExerciseDate(date);
        s.setStartedAt(startedAt);
        s.setDurationMin(duration);
        s.setTypeCode(type.code());
        s.setTypeName(type.name());
        s.setCategory(category.name());
        s.setIntensity(intensity.name());
        s.setAvgHr(input.avgHr());
        s.setDistanceM(input.distanceM());
        s.setMetValue(activity.met());
        s.setActiveMin(activity.activeMin());
        s.setEnergyKcal(activity.energyKcal());
        s.setWeightEstimated(activity.weightEstimated() ? 1 : 0);
        s.setSymptomsJson(symptoms.isEmpty() ? null : JsonUtils.toJson(symptoms.stream().map(Enum::name).toList()));
        s.setSymptomLevel(rules.symptomLevel());
        s.setFlagsJson(JsonUtils.toJson(flags));
        s.setLevel(rules.level());
        s.setCarePlanTaskId(planTaskId);
        if (s.getSource() == null) {
            s.setSource(source.name());
        }
        s.setNote(note);
        return symptoms;
    }

    private void raiseAlert(ExerciseSession s, List<ExerciseSymptomEnum> symptoms) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("sessionId", s.getId());
        event.put("symptoms", symptoms.stream().map(Enum::name).toList());
        event.put("symptomLabels", symptoms.stream().map(ExerciseSymptomEnum::label).toList());
        event.put("typeName", s.getTypeName());
        event.put("startedAt", s.getStartedAt().toString());
        event.put("durationMin", s.getDurationMin());
        event.put("intensity", s.getIntensity());
        event.put("recordedByStaff", StringUtils.hasText(s.getRecordedByStaffId()));
        event.put("reportedAt", LocalDateTime.now(JobCronSupport.ZONE).toString());
        String taskId = workspaceTaskGenerator.onExerciseSymptom(s.getTenantId(), s.getPeopleId(), event);
        if (taskId == null) {
            log.warn("exercise RED symptom without care org, people={} session={}", s.getPeopleId(), s.getId());
            return;
        }
        s.setAlertTaskId(taskId);
        sessionMapper.updateAlertTask(s.getId(), taskId);
    }

    /**
     * 记录事务提交后再同步打卡：打卡失败不能把记录的保存/删除一起回滚。
     *
     * <p>afterCommit 时原事务资源仍绑定在线程上，须 REQUIRES_NEW 才会真正提交。
     */
    private void syncCheckinsAfterCommit(String tenantId, String peopleId, LocalDate... dates) {
        Runnable sync = () -> {
            for (LocalDate date : java.util.Arrays.stream(dates).distinct().toList()) {
                try {
                    requiresNew.executeWithoutResult(st -> syncCheckins(tenantId, peopleId, date));
                } catch (RuntimeException e) {
                    log.warn("sync EXERCISE checkin failed people={} date={}: {}", peopleId, date, e.getMessage());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync.run();
                }
            });
        } else {
            sync.run();
        }
    }

    /** 当日到期的运动任务逐个判定：同大类累计时长 ≥ 目标 → 系统打卡，否则撤销系统打卡。 */
    private void syncCheckins(String tenantId, String peopleId, LocalDate date) {
        ExercisePlan plan = planLoader.load(tenantId, peopleId, today());
        List<TaskTarget> due = plan.dueOn(date);
        if (due.isEmpty()) {
            return;
        }
        List<ExerciseSession> sessions = sessionMapper.listByPeopleRange(tenantId, peopleId, date, date);
        for (TaskTarget tt : due) {
            boolean done = minutesOf(sessions, tt.target().category()) >= tt.target().targetMin();
            checkinService.syncSystemCheckin(
                    tenantId, peopleId, tt.task().getTaskCode(), date, done, peopleId);
        }
    }

    private Map<LocalDate, Integer> dailySteps(String tenantId, String peopleId, LocalDate start, LocalDate end) {
        List<VitalRecord> rows = vitalRecordMapper.listByPeople(
                tenantId, peopleId, MetricTypeEnum.STEPS.name(), start.atStartOfDay(), end.plusDays(1).atStartOfDay(), 500);
        Map<LocalDate, Integer> out = new HashMap<>();
        for (VitalRecord r : rows) {
            if (r.getValue() == null || r.getRecordedAt() == null) {
                continue;
            }
            out.merge(r.getRecordedAt().toLocalDate(), r.getValue().intValue(), Math::max);
        }
        return out;
    }

    private static int minutesOf(List<ExerciseSession> sessions, ExerciseCategoryEnum category) {
        return sessions.stream()
                .filter(s -> category.name().equals(s.getCategory()))
                .mapToInt(s -> nz(s.getDurationMin()))
                .sum();
    }

    private static String worstLevel(List<ExerciseSession> sessions) {
        if (sessions.stream().anyMatch(s -> ExerciseRuleEngine.RED.equals(s.getLevel()))) {
            return ExerciseRuleEngine.RED;
        }
        if (sessions.stream().anyMatch(s -> ExerciseRuleEngine.YELLOW.equals(s.getLevel()))) {
            return ExerciseRuleEngine.YELLOW;
        }
        return ExerciseRuleEngine.GREEN;
    }

    private static void assertWritable(ExerciseSession s, Actor actor) {
        boolean byStaff = StringUtils.hasText(s.getRecordedByStaffId());
        if (actor.isPatient() && byStaff) {
            throw new BusinessException(400, "健管师代录的记录不可修改");
        }
        if (!actor.isPatient() && !byStaff) {
            throw new BusinessException(400, "患者自行记录的数据不可修改");
        }
    }

    private ExerciseSession requireSession(String tenantId, String peopleId, String id) {
        ExerciseSession s = sessionMapper.findById(id);
        if (s == null || !tenantId.equals(s.getTenantId()) || !peopleId.equals(s.getPeopleId())) {
            throw new BusinessException(404, "运动记录不存在");
        }
        return s;
    }

    private static LocalDate today() {
        return LocalDate.now(JobCronSupport.ZONE);
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ DTO

    private static ExerciseSessionDto toDto(ExerciseSession s, boolean patientView) {
        ExerciseSessionDto d = new ExerciseSessionDto();
        d.setId(s.getId());
        d.setExerciseDate(s.getExerciseDate());
        d.setStartedAt(s.getStartedAt());
        d.setDurationMin(s.getDurationMin());
        d.setTypeCode(s.getTypeCode());
        d.setTypeName(s.getTypeName());
        d.setCategory(s.getCategory());
        d.setIntensity(s.getIntensity());
        d.setAvgHr(s.getAvgHr());
        d.setDistanceM(s.getDistanceM());
        d.setMetValue(s.getMetValue());
        d.setActiveMin(s.getActiveMin());
        d.setEnergyKcal(s.getEnergyKcal());
        d.setWeightEstimated(Objects.equals(s.getWeightEstimated(), 1));
        d.setSymptoms(parseList(s.getSymptomsJson()));
        d.setSymptomLevel(s.getSymptomLevel());
        d.setFlags(parseFlags(s.getFlagsJson()));
        d.setLevel(s.getLevel());
        d.setAlertTaskId(s.getAlertTaskId());
        d.setCarePlanTaskId(s.getCarePlanTaskId());
        d.setSource(s.getSource());
        d.setNote(s.getNote());
        boolean byStaff = StringUtils.hasText(s.getRecordedByStaffId());
        d.setRecordedByStaff(byStaff);
        d.setEditable(patientView != byStaff);
        return d;
    }

    private static List<String> parseList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> list = JsonUtils.fromJson(json, new TypeReference<List<String>>() {});
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static List<ExerciseFlagDto> parseFlags(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<ExerciseFlagDto> list = JsonUtils.fromJson(json, new TypeReference<List<ExerciseFlagDto>>() {});
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
