package com.healix.core.exercise.support;

import com.healix.core.exercise.dto.ExerciseTypeDto;
import com.healix.core.exercise.enums.IntensityEnum;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** MET、活跃分钟（WHO 口径）、热量估算。 */
@Component
@RequiredArgsConstructor
public class ActivityCalculator {

    public static final double FALLBACK_WEIGHT_KG = 60.0;

    private final VitalRecordMapper vitalRecordMapper;

    public record Activity(double met, int activeMin, double energyKcal, boolean weightEstimated) {}

    public Activity compute(ExerciseTypeDto type, IntensityEnum intensity, int durationMin, Double weightKg) {
        double met = ExerciseCatalog.metOf(type, intensity);
        boolean estimated = weightKg == null || weightKg <= 0;
        double weight = estimated ? FALLBACK_WEIGHT_KG : weightKg;
        double kcal = met * weight * durationMin / 60.0;
        return new Activity(met, durationMin * intensity.activeFactor(), Math.round(kcal * 10.0) / 10.0, estimated);
    }

    /** 最近一次体重；无记录返回 null。 */
    public Double latestWeightKg(String tenantId, String peopleId) {
        LocalDateTime now = LocalDateTime.now();
        List<VitalRecord> rows = vitalRecordMapper.listByPeople(
                tenantId, peopleId, MetricTypeEnum.WEIGHT.name(), now.minusYears(5), now, 1);
        if (rows.isEmpty() || rows.get(0).getValue() == null) {
            return null;
        }
        return rows.get(0).getValue().doubleValue();
    }
}
