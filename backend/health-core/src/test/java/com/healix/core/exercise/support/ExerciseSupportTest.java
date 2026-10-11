package com.healix.core.exercise.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.exercise.enums.ExerciseCategoryEnum;
import com.healix.core.exercise.enums.ExerciseSymptomEnum;
import com.healix.core.exercise.enums.IntensityEnum;
import com.healix.core.exercise.support.ExercisePlanLoader.ExercisePlan;
import com.healix.core.exercise.support.ExercisePlanLoader.PlanState;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExerciseSupportTest {

    private final ExerciseCatalog catalog = new ExerciseCatalog();
    private final ActivityCalculator calculator = new ActivityCalculator(null);
    private final ExerciseRuleEngine rules = new ExerciseRuleEngine();
    private final ExerciseTaskTargetResolver resolver = new ExerciseTaskTargetResolver(catalog);

    private static ExercisePlan plan(CarePlanTemplateKeyEnum disease) {
        return new ExercisePlan(PlanState.NONE, null, 0, List.of(), 150, List.of(), List.of(), disease, false);
    }

    @Test
    void catalogLoadsAboutFortyTypes() {
        assertTrue(catalog.all().size() >= 35);
        assertEquals("快走", catalog.search("健步走", 5).get(0).name());
    }

    @Test
    void activeMinutesFollowWho() {
        var brisk = catalog.require("BRISK_WALKING");
        assertEquals(30, calculator.compute(brisk, IntensityEnum.MODERATE, 30, 70.0).activeMin());
        assertEquals(60, calculator.compute(brisk, IntensityEnum.VIGOROUS, 30, 70.0).activeMin());
        assertEquals(0, calculator.compute(brisk, IntensityEnum.LIGHT, 30, 70.0).activeMin());
    }

    @Test
    void energyIsMetTimesWeightTimesHours() {
        var a = calculator.compute(catalog.require("BRISK_WALKING"), IntensityEnum.MODERATE, 30, 70.0);
        assertEquals(150.5, a.energyKcal());
        assertFalse(a.weightEstimated());
        var b = calculator.compute(catalog.require("BRISK_WALKING"), IntensityEnum.MODERATE, 30, null);
        assertEquals(129.0, b.energyKcal());
        assertTrue(b.weightEstimated());
    }

    @Test
    void hypoSymptomRedOnlyForDiabetes() {
        var walk = catalog.require("WALKING");
        var diabetic = rules.evaluate(
                walk, IntensityEnum.LIGHT, 20, List.of(ExerciseSymptomEnum.HYPO), plan(CarePlanTemplateKeyEnum.DIABETES), null);
        assertEquals("RED", diabetic.symptomLevel());
        var general = rules.evaluate(
                walk, IntensityEnum.LIGHT, 20, List.of(ExerciseSymptomEnum.HYPO), plan(CarePlanTemplateKeyEnum.GENERAL), null);
        assertEquals("YELLOW", general.symptomLevel());
        var chest = rules.evaluate(
                walk, IntensityEnum.LIGHT, 20, List.of(ExerciseSymptomEnum.CHEST_PAIN), plan(CarePlanTemplateKeyEnum.GENERAL), null);
        assertEquals("RED", chest.level());
    }

    @Test
    void breathHoldWarnsHypertension() {
        var r = rules.evaluate(
                catalog.require("MACHINE_STRENGTH"), IntensityEnum.MODERATE, 20, List.of(),
                plan(CarePlanTemplateKeyEnum.HYPERTENSION), null);
        assertTrue(r.flags().stream().anyMatch(f -> "BREATH_HOLD".equals(f.code())));
    }

    @Test
    void overlapMoreThanHalfOfShorterIsSuspectedDuplicate() {
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 11, 18, 0);
        var walk = new ExerciseRuleEngine.TimeSlot(t0, 30, "快走");
        var hit = ExerciseRuleEngine.overlapFlag(t0.plusMinutes(10), 30, List.of(walk));
        assertEquals("DUPLICATE_SUSPECT", hit.code());
        assertTrue(hit.message().contains("18:00"));
        assertNull(ExerciseRuleEngine.overlapFlag(t0.plusMinutes(20), 30, List.of(walk)));
        assertNull(ExerciseRuleEngine.overlapFlag(t0.plusMinutes(30), 30, List.of(walk)));
        assertEquals("DUPLICATE_SUSPECT",
                ExerciseRuleEngine.overlapFlag(t0.minusMinutes(60), 120, List.of(walk)).code());
    }

    @Test
    void legacyTaskFallsBackToWeeklyPlanThenDefault() {
        CarePlanTask task = new CarePlanTask();
        task.setRelatedRef("WALKING");
        var weekly = JsonUtils.readTree(
                "{\"weeklyPlan\":[{\"day\":\"MON\",\"items\":[{\"type\":\"WALKING\",\"durationMin\":25,\"intensity\":\"LIGHT\"}]}]}");
        var fromPlan = resolver.resolve(task, weekly, false);
        assertEquals(25, fromPlan.targetMin());
        assertEquals(IntensityEnum.LIGHT, fromPlan.intensity());
        assertEquals(ExerciseCategoryEnum.AEROBIC, fromPlan.category());

        var fallback = resolver.resolve(task, JsonUtils.emptyObject(), true);
        assertEquals(20, fallback.targetMin());

        task.setPayloadJson("{\"targetMin\":40,\"intensity\":\"MODERATE\"}");
        assertEquals(40, resolver.resolve(task, weekly, false).targetMin());

        task.setPayloadJson(null);
        task.setRelatedRef("ELASTIC_BAND");
        assertEquals(ExerciseCategoryEnum.RESISTANCE, resolver.resolve(task, null, false).category());
    }

    @Test
    void aiPlanCodeAliasesResolveToCatalogTypes() {
        CarePlanTask task = new CarePlanTask();
        task.setRelatedRef("BREATHING_TRAINING");
        var breathing = resolver.resolve(task, null, false);
        assertEquals("BREATHING", breathing.typeCode());
        assertEquals(ExerciseCategoryEnum.FLEXIBILITY, breathing.category());

        task.setRelatedRef("STRENGTH");
        assertEquals(ExerciseCategoryEnum.RESISTANCE, resolver.resolve(task, null, false).category());

        task.setRelatedRef("RESISTANCE");
        assertNull(resolver.resolve(task, null, false).typeCode());

        assertEquals("ANKLE_PUMP", catalog.require("bedside_ankle_pump").code());
    }
}
