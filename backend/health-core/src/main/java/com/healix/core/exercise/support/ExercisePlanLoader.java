package com.healix.core.exercise.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.core.careplan.enums.CarePlanStatusEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.careplan.support.CarePlanDueSupport;
import com.healix.core.diet.support.DietContextLoader;
import com.healix.core.exercise.support.ExerciseTaskTargetResolver.ExerciseTarget;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 读取生效管理方案中的运动计划：状态、运动任务目标、禁忌/注意事项、周活跃分钟目标、病种。 */
@Component
@RequiredArgsConstructor
public class ExercisePlanLoader {

    public static final String CATEGORY_EXERCISE = "EXERCISE";
    public static final int DEFAULT_WEEKLY_ACTIVE_MIN = 150;

    public enum PlanState {
        /** 有生效方案且日期在执行窗口内 */
        ACTIVE,
        /** 方案已过执行窗口 */
        EXPIRED,
        /** 无生效方案 */
        NONE
    }

    public record TaskTarget(CarePlanTask task, ExerciseTarget target) {}

    public record ExercisePlan(
            PlanState state,
            LocalDate planStart,
            int horizonDays,
            List<TaskTarget> tasks,
            int weeklyActiveMin,
            List<String> contraindications,
            List<String> precautions,
            CarePlanTemplateKeyEnum diseaseKey,
            boolean hypoglycemiaRisk) {

        public boolean diabetes() {
            return diseaseKey == CarePlanTemplateKeyEnum.DIABETES
                    || diseaseKey == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION;
        }

        public boolean hypertension() {
            return diseaseKey == CarePlanTemplateKeyEnum.HYPERTENSION
                    || diseaseKey == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION;
        }

        /** 某日应打的运动任务（频率 + 执行窗口）。 */
        public List<TaskTarget> dueOn(LocalDate day) {
            if (state == PlanState.NONE || planStart == null) {
                return List.of();
            }
            return tasks.stream()
                    .filter(t -> CarePlanDueSupport.isDueOnDate(t.task(), day, planStart, horizonDays))
                    .toList();
        }
    }

    private final CarePlanMapper carePlanMapper;
    private final CarePlanVersionMapper versionMapper;
    private final CarePlanTaskMapper taskMapper;
    private final ExerciseTaskTargetResolver targetResolver;
    private final DietContextLoader dietContextLoader;

    public ExercisePlan load(String tenantId, String peopleId, LocalDate today) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        CarePlanVersion version = null;
        if (plan != null
                && CarePlanStatusEnum.ACTIVE.name().equals(plan.getStatus())
                && StringUtils.hasText(plan.getCurrentVersionId())) {
            version = versionMapper.findById(plan.getCurrentVersionId());
        }
        if (version == null) {
            CarePlanTemplateKeyEnum disease = dietContextLoader.load(tenantId, peopleId).diseaseKey();
            return new ExercisePlan(
                    PlanState.NONE, null, 0, List.of(), DEFAULT_WEEKLY_ACTIVE_MIN, List.of(), List.of(), disease, false);
        }

        JsonNode exercise = StringUtils.hasText(version.getExerciseJson())
                ? JsonUtils.readTree(version.getExerciseJson())
                : JsonUtils.emptyObject();
        JsonNode snapshot = StringUtils.hasText(version.getContextSnapshotJson())
                ? JsonUtils.readTree(version.getContextSnapshotJson())
                : JsonUtils.emptyObject();
        boolean hypo = snapshot.path("hypoglycemiaRisk").asBoolean(false);
        CarePlanTemplateKeyEnum disease = parseTemplateKey(snapshot.path("templateKey").asText(null));
        if (disease == null) {
            disease = dietContextLoader.load(tenantId, peopleId).diseaseKey();
        }

        LocalDate planStart = version.getPublishedAt() != null ? version.getPublishedAt().toLocalDate() : today;
        int horizonDays = CarePlanDueSupport.resolveHorizonDays(version.getExecutionJson());
        PlanState state = CarePlanDueSupport.isWithinHorizon(today, planStart, horizonDays)
                        || today.isBefore(planStart)
                ? PlanState.ACTIVE
                : PlanState.EXPIRED;

        List<TaskTarget> tasks = new ArrayList<>();
        for (CarePlanTask t : taskMapper.listActiveByPeople(tenantId, peopleId)) {
            if (!CATEGORY_EXERCISE.equals(t.getCategory()) || (t.getEnabled() != null && t.getEnabled() == 0)) {
                continue;
            }
            tasks.add(new TaskTarget(t, targetResolver.resolve(t, exercise, hypo)));
        }

        JsonNode weekly = exercise.path("targets").get("weeklyActiveMin");
        int weeklyActiveMin = weekly != null && weekly.canConvertToInt() && weekly.asInt() >= 0
                ? weekly.asInt()
                : (hypo ? 0 : DEFAULT_WEEKLY_ACTIVE_MIN);

        return new ExercisePlan(
                state,
                planStart,
                horizonDays,
                List.copyOf(tasks),
                weeklyActiveMin,
                texts(exercise.path("contraindications")),
                texts(exercise.path("precautions")),
                disease,
                hypo);
    }

    private static CarePlanTemplateKeyEnum parseTemplateKey(String v) {
        if (!StringUtils.hasText(v)) {
            return null;
        }
        try {
            return CarePlanTemplateKeyEnum.valueOf(v.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> {
            if (n.isTextual() && StringUtils.hasText(n.asText())) {
                out.add(n.asText().trim());
            }
        });
        return out;
    }
}
