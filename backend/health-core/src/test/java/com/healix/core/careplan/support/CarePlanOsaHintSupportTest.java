package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class CarePlanOsaHintSupportTest {

    @Test
    void injectsStrongHintWhenSnoringFatigueAndObesity() {
        ObjectNode snap = JsonUtils.readTree("""
                {"diseaseCodes":["DIABETES","HYPERTENSION"],
                 "basicArchive":{"sleep":{"disorder":"SNORING","refreshed":"PARTIAL","hours":6}},
                 "latestMetrics":[{"metricType":"BMI","value":"27.7"}]}
                """).deepCopy();
        assertTrue(CarePlanOsaHintSupport.shouldHint(snap));

        ObjectNode diet = JsonUtils.emptyObject();
        diet.put("notes", "关注睡眠呼吸情况；进餐顺序：先菜后主食。");
        ObjectNode exercise = JsonUtils.emptyObject();
        exercise.putArray("precautions").add("关注睡眠呼吸情况").add("运动时不要憋气");

        CarePlanOsaHintSupport.apply(diet, exercise, snap);

        assertTrue(diet.path("notes").asText().contains("睡眠呼吸监测"));
        assertFalse(diet.path("notes").asText().contains("关注睡眠呼吸情况"));
        assertTrue(exercise.path("precautions").toString().contains("多导睡眠"));
        assertFalse(exercise.path("precautions").toString().contains("关注睡眠呼吸情况"));
        assertTrue(exercise.path("precautions").toString().contains("不要憋气"));
    }

    @Test
    void skipsWhenNoSnoring() {
        ObjectNode snap = JsonUtils.readTree("""
                {"diseaseCodes":["OBESITY"],
                 "basicArchive":{"sleep":{"disorder":"NONE","refreshed":"NO"}}}
                """).deepCopy();
        assertFalse(CarePlanOsaHintSupport.shouldHint(snap));
    }
}
