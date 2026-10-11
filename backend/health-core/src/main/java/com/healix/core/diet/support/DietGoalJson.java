package com.healix.core.diet.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.dto.DietGoalDto;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/** DietGoal 实体与 DTO 之间的转换。 */
public final class DietGoalJson {

    private DietGoalJson() {}

    public static Map<String, Double> mealSplit(DietGoal goal) {
        if (goal != null && StringUtils.hasText(goal.getMealSplitJson())) {
            try {
                Map<String, Double> m = JsonUtils.fromJson(goal.getMealSplitJson(), new TypeReference<LinkedHashMap<String, Double>>() {});
                if (m != null && !m.isEmpty()) {
                    return m;
                }
            } catch (RuntimeException ignored) {
                // 回退默认分配
            }
        }
        return EnergyTargetCalculator.defaultMealSplit();
    }

    public static String diseaseLabel(String diseaseKey) {
        if (!StringUtils.hasText(diseaseKey)) {
            return "一般人群";
        }
        try {
            return switch (CarePlanTemplateKeyEnum.valueOf(diseaseKey)) {
                case DIABETES -> "糖尿病";
                case HYPERTENSION -> "高血压";
                case DIABETES_HYPERTENSION -> "糖尿病合并高血压";
                default -> "一般人群";
            };
        } catch (IllegalArgumentException e) {
            return "一般人群";
        }
    }

    public static DietGoalDto toDto(DietGoal g) {
        if (g == null) {
            return null;
        }
        DietGoalDto d = new DietGoalDto();
        d.setId(g.getId());
        d.setSource(g.getSource());
        d.setGoalType(g.getGoalType());
        d.setRateKgPerWeek(g.getRateKgPerWeek());
        d.setActivityLevel(g.getActivityLevel());
        d.setDiseaseKey(g.getDiseaseKey());
        d.setDiseaseLabel(diseaseLabel(g.getDiseaseKey()));
        d.setBasisWeightKg(g.getBasisWeightKg());
        d.setBasisHeightCm(g.getBasisHeightCm());
        d.setBasisAge(g.getBasisAge());
        d.setBasisGender(g.getBasisGender());
        d.setBmrKcal(g.getBmrKcal());
        d.setTdeeKcal(g.getTdeeKcal());
        d.setEnergyKcal(g.getEnergyKcal());
        d.setCarbG(g.getCarbG());
        d.setProteinG(g.getProteinG());
        d.setFatG(g.getFatG());
        d.setFiberG(g.getFiberG());
        d.setSodiumMg(g.getSodiumMg());
        d.setMealSplit(mealSplit(g));
        d.setFloorApplied(g.getFloorApplied() != null && g.getFloorApplied() == 1);
        d.setNote(g.getNote());
        d.setUpdatedAt(g.getGmtCreated());
        return d;
    }
}
