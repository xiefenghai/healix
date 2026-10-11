package com.healix.core.diet.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.diet.enums.ActivityLevelEnum;
import com.healix.core.diet.enums.DietGoalTypeEnum;
import org.junit.jupiter.api.Test;

class EnergyTargetCalculatorTest {

    @Test
    void maleLoseModerateDiabetes() {
        var r = EnergyTargetCalculator.calculate(new EnergyTargetCalculator.Input(
                "MALE", 34, 177, 68.5, ActivityLevelEnum.MODERATE, DietGoalTypeEnum.LOSE, 0.5,
                CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION));
        assertEquals(1626, r.bmrKcal());
        assertEquals(2521, r.tdeeKcal());
        assertEquals(1970, r.energyKcal());
        assertEquals(246, r.carbG());
        assertFalse(r.floorApplied());
    }

    @Test
    void femaleFloorApplied() {
        var r = EnergyTargetCalculator.calculate(new EnergyTargetCalculator.Input(
                "FEMALE", 60, 150, 45, ActivityLevelEnum.SEDENTARY, DietGoalTypeEnum.LOSE, 1.0,
                CarePlanTemplateKeyEnum.GENERAL));
        assertEquals(EnergyTargetCalculator.FEMALE_FLOOR_KCAL, r.energyKcal());
        assertTrue(r.floorApplied());
    }

    @Test
    void planLimitedCodesMapToFoodTags() {
        assertTrue(DietContextLoader.foodTagsFor("REFINED_SUGAR", "精制糖及含糖饮料、甜点").contains("SUGARY_DRINK"));
        assertTrue(DietContextLoader.foodTagsFor("PICKLED_FOOD", "腌制、酱制食品").contains("PICKLED"));
        assertTrue(DietContextLoader.foodTagsFor("FRIED_FOOD", "油炸及高脂食物").contains("FRIED"));
        assertTrue(DietContextLoader.foodTagsFor("ALCOHOL", "酒精饮品").contains("ALCOHOL"));
        assertTrue(DietContextLoader.foodTagsFor("HOT_POT", "火锅").isEmpty());
    }
}
