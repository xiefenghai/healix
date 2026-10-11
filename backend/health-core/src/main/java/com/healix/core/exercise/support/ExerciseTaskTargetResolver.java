package com.healix.core.exercise.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.exercise.dto.ExerciseTypeDto;
import com.healix.core.exercise.enums.ExerciseCategoryEnum;
import com.healix.core.exercise.enums.IntensityEnum;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 运动任务 → 计划目标（目标时长、强度、匹配大类）。
 *
 * <p>老方案（schema V1）任务无 payload，依次回退：任务 payload → 版本 weeklyPlan 同类型条目 → 默认值。
 * 方案版本是医疗快照，不做数据回填，兼容逻辑集中在此。
 */
@Component
@RequiredArgsConstructor
public class ExerciseTaskTargetResolver {

    public static final int DEFAULT_TARGET_MIN = 30;
    public static final int HYPO_TARGET_MIN = 20;

    private final ExerciseCatalog catalog;

    public record ExerciseTarget(
            String taskId,
            String taskCode,
            String title,
            String typeCode,
            ExerciseCategoryEnum category,
            int targetMin,
            IntensityEnum intensity) {}

    public ExerciseTarget resolve(CarePlanTask task, JsonNode exerciseJson, boolean hypoglycemiaRisk) {
        JsonNode payload = StringUtils.hasText(task.getPayloadJson()) ? JsonUtils.readTree(task.getPayloadJson()) : null;
        String ref = StringUtils.hasText(task.getRelatedRef()) ? task.getRelatedRef().trim().toUpperCase(Locale.ROOT) : null;
        JsonNode planItem = findWeeklyItem(exerciseJson, ref);

        Integer targetMin = positiveInt(payload, "targetMin");
        if (targetMin == null) {
            targetMin = positiveInt(planItem, "durationMin");
        }
        if (targetMin == null) {
            targetMin = hypoglycemiaRisk ? HYPO_TARGET_MIN : DEFAULT_TARGET_MIN;
        }

        IntensityEnum fallbackIntensity = hypoglycemiaRisk ? IntensityEnum.LIGHT : IntensityEnum.MODERATE;
        IntensityEnum intensity = IntensityEnum.parseOr(text(payload, "intensity"), null);
        if (intensity == null) {
            intensity = IntensityEnum.parseOr(text(planItem, "intensity"), fallbackIntensity);
        }

        String typeCode = catalog.find(ref).map(ExerciseTypeDto::code).orElse(null);
        return new ExerciseTarget(
                task.getId(), task.getTaskCode(), task.getTitle(), typeCode, categoryOf(ref), targetMin, intensity);
    }

    /** relatedRef 可能是类型编码（WALKING / 别名），也可能直接写大类（RESISTANCE）。 */
    private ExerciseCategoryEnum categoryOf(String ref) {
        if (ref == null) {
            return ExerciseCategoryEnum.AEROBIC;
        }
        ExerciseCategoryEnum direct = ExerciseCategoryEnum.parseOr(ref, null);
        if (direct != null) {
            return direct;
        }
        return catalog.find(ref).map(ExerciseCatalog::categoryOf).orElse(ExerciseCategoryEnum.AEROBIC);
    }

    private static JsonNode findWeeklyItem(JsonNode exerciseJson, String ref) {
        if (exerciseJson == null || ref == null) {
            return null;
        }
        for (JsonNode day : exerciseJson.path("weeklyPlan")) {
            for (JsonNode item : day.path("items")) {
                if (ref.equalsIgnoreCase(item.path("type").asText(""))) {
                    return item;
                }
            }
        }
        return null;
    }

    private static Integer positiveInt(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || !v.canConvertToInt()) {
            return null;
        }
        int i = v.asInt();
        return i > 0 ? i : null;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
