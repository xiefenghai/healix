package com.healix.core.exercise.support;

import com.healix.core.exercise.dto.ExerciseFlagDto;
import com.healix.core.exercise.dto.ExerciseTypeDto;
import com.healix.core.exercise.enums.ExerciseSymptomEnum;
import com.healix.core.exercise.enums.IntensityEnum;
import com.healix.core.exercise.support.ExercisePlanLoader.ExercisePlan;
import com.healix.core.exercise.support.ExerciseTaskTargetResolver.ExerciseTarget;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 运动记录规则（P1）：症状分级、强度超计划、高血压憋气类运动、超长时长、时间重叠疑似重复。
 *
 * <p>提示不阻止保存；RED 由记录服务开 EXERCISE_ALERT 工作台任务。
 */
@Component
public class ExerciseRuleEngine {

    public static final String RED = "RED";
    public static final String YELLOW = "YELLOW";
    public static final String INFO = "INFO";
    public static final String GREEN = "GREEN";
    public static final String NONE = "NONE";
    public static final int LONG_DURATION_MIN = 240;

    public record Result(List<ExerciseFlagDto> flags, String symptomLevel, String level) {}

    /**
     * @param matched 当日与本次运动同大类的计划任务；无计划时为 null
     */
    public Result evaluate(
            ExerciseTypeDto type,
            IntensityEnum intensity,
            int durationMin,
            List<ExerciseSymptomEnum> symptoms,
            ExercisePlan plan,
            ExerciseTarget matched) {
        List<ExerciseFlagDto> flags = new ArrayList<>();

        List<ExerciseSymptomEnum> red = symptoms.stream()
                .filter(s -> s.alwaysRed() || (s == ExerciseSymptomEnum.HYPO && plan.diabetes()))
                .toList();
        List<ExerciseSymptomEnum> yellow = symptoms.stream().filter(s -> !red.contains(s)).toList();
        if (!red.isEmpty()) {
            String msg = "运动后出现" + labels(red) + "，请立即停止运动并休息；症状持续请拨打 120。已通知您的健管师。";
            if (red.contains(ExerciseSymptomEnum.HYPO)) {
                msg += "低血糖时请立即补充 15g 糖（如 3–4 块方糖）。";
            }
            flags.add(new ExerciseFlagDto("SYMPTOM_RED", RED, msg));
        }
        if (!yellow.isEmpty()) {
            flags.add(new ExerciseFlagDto(
                    "SYMPTOM_YELLOW", YELLOW, "运动后出现" + labels(yellow) + "，请注意休息，下次适当降低强度。"));
        }
        if (matched != null && intensity.ordinal() > matched.intensity().ordinal()) {
            flags.add(new ExerciseFlagDto(
                    "OVER_INTENSITY", YELLOW, "本次强度高于运动计划要求，请按计划强度循序渐进。"));
        }
        if (plan.hypertension() && type.tags().contains("BREATH_HOLD")) {
            flags.add(new ExerciseFlagDto(
                    "BREATH_HOLD", YELLOW, "高血压人群做力量训练请避免憋气和大重量，用力时呼气。"));
        }
        if (durationMin > LONG_DURATION_MIN) {
            flags.add(new ExerciseFlagDto("DURATION_ANOMALY", INFO, "单次运动超过 4 小时，请确认时长是否正确。"));
        }

        String symptomLevel = !red.isEmpty() ? RED : (!yellow.isEmpty() ? YELLOW : NONE);
        return new Result(List.copyOf(flags), symptomLevel, levelOf(flags));
    }

    public static String levelOf(List<ExerciseFlagDto> flags) {
        if (flags.stream().anyMatch(f -> RED.equals(f.level()))) {
            return RED;
        }
        if (flags.stream().anyMatch(f -> YELLOW.equals(f.level()))) {
            return YELLOW;
        }
        return GREEN;
    }

    /** 已有记录的时间窗，用于疑似重复判定。 */
    public record TimeSlot(LocalDateTime startedAt, int durationMin, String typeName) {}

    /**
     * 与任一已有记录的重叠时长超过两者中较短一条的一半 → INFO 提示疑似重复（不拦截）。
     */
    public static ExerciseFlagDto overlapFlag(LocalDateTime startedAt, int durationMin, List<TimeSlot> others) {
        LocalDateTime end = startedAt.plusMinutes(durationMin);
        for (TimeSlot o : others) {
            if (o.startedAt() == null || o.durationMin() <= 0) {
                continue;
            }
            LocalDateTime oEnd = o.startedAt().plusMinutes(o.durationMin());
            LocalDateTime from = startedAt.isAfter(o.startedAt()) ? startedAt : o.startedAt();
            LocalDateTime to = end.isBefore(oEnd) ? end : oEnd;
            long overlap = Duration.between(from, to).toMinutes();
            if (overlap > 0 && overlap * 2 > Math.min(durationMin, o.durationMin())) {
                return new ExerciseFlagDto(
                        "DUPLICATE_SUSPECT",
                        INFO,
                        String.format("与 %02d:%02d 的「%s」时间重叠，可能重复记录。",
                                o.startedAt().getHour(), o.startedAt().getMinute(), o.typeName()));
            }
        }
        return null;
    }

    public static String labels(List<ExerciseSymptomEnum> symptoms) {
        return symptoms.stream().map(ExerciseSymptomEnum::label).collect(Collectors.joining("、"));
    }
}
