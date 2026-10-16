package com.healix.core.water.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WaterIntakeRulesTest {

    @Test
    void suggestTargetUsesWeightAndClamps() {
        assertEquals(1600, WaterIntakeRules.suggestTargetMl(null));
        assertEquals(1800, WaterIntakeRules.suggestTargetMl(60.0));
        assertEquals(1200, WaterIntakeRules.suggestTargetMl(30.0));
        assertEquals(2000, WaterIntakeRules.suggestTargetMl(100.0));
    }

    @Test
    void fluidRestrictedDetectsKeywords() {
        assertTrue(WaterIntakeRules.fluidRestricted(List.of("CHRONIC_KIDNEY"), null));
        assertTrue(WaterIntakeRules.fluidRestricted(List.of(), com.healix.common.util.JsonUtils.readTree("{\"x\":\"心衰患者\"}")));
        assertFalse(WaterIntakeRules.fluidRestricted(List.of("DIABETES"), null));
    }

    @Test
    void looksLikeWaterTask() {
        assertTrue(WaterIntakeRules.looksLikeWaterTask("DIET", "足量饮水，每日约 1600 ml", "X"));
        assertTrue(WaterIntakeRules.looksLikeWaterTask("WATER", "今日饮水", "WATER_LOG"));
        assertFalse(WaterIntakeRules.looksLikeWaterTask("DIET", "记录三餐", "DIET_LOG"));
    }
}
