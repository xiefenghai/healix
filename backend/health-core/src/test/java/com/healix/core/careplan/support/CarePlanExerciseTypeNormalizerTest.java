package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CarePlanExerciseTypeNormalizerTest {

    @Test
    void canonicalizeMapsRelaxationAliases() {
        assertEquals("RELAXATION", CarePlanExerciseTypeNormalizer.canonicalize("RELAX"));
        assertEquals("RELAXATION", CarePlanExerciseTypeNormalizer.canonicalize("cool-down"));
        assertEquals("RELAXATION", CarePlanExerciseTypeNormalizer.canonicalize("放松"));
        assertEquals("STRETCHING", CarePlanExerciseTypeNormalizer.canonicalize("FLEXIBILITY"));
        assertEquals("BREATHING", CarePlanExerciseTypeNormalizer.canonicalize("breathwork"));
    }

    @Test
    void normalizeWeeklyPlanTypesInPlace() {
        ObjectNode exercise = JsonUtils.emptyObject();
        ArrayNode days = exercise.putArray("weeklyPlan");
        ObjectNode mon = days.addObject();
        mon.put("day", "MON");
        ArrayNode items = mon.putArray("items");
        items.addObject().put("type", "RELAX").put("durationMin", 5);
        items.addObject().put("type", "WALKING").put("durationMin", 20);

        CarePlanExerciseTypeNormalizer.normalize(exercise);

        assertEquals("RELAXATION", mon.path("items").get(0).path("type").asText());
        assertEquals("WALKING", mon.path("items").get(1).path("type").asText());
    }
}
