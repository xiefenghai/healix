package com.healix.core.careplan.template;

import static org.assertj.core.api.Assertions.assertThat;

import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.guideline.GuidelineCheck;
import com.healix.core.careplan.guideline.GuidelineFacts;
import com.healix.core.careplan.guideline.GuidelineLibrary;
import com.healix.core.careplan.guideline.GuidelineMatch;
import com.healix.core.careplan.guideline.GuidelineMatcher;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanDiseaseTags;
import com.healix.core.careplan.support.MetricTaskRules;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CarePlanTemplateRegistryTest {

    private final CarePlanTemplateRegistry registry = new CarePlanTemplateRegistry();

    @Test
    void diabetesPlusHypertensionStacksBothMetricTasksEvenOnDiabetesTemplate() {
        CarePlanContext ctx = ctx(List.of("DIABETES", "HYPERTENSION"));
        var plan = registry.generate(CarePlanTemplateKeyEnum.DIABETES, ctx, null, null);
        assertThat(plan.templateKey()).isEqualTo(CarePlanTemplateKeyEnum.DIABETES);
        assertThat(plan.specialtyMismatch()).isFalse();
        String exec = plan.execution().toString();
        assertThat(exec).contains("GLUCOSE_CHECK");
        assertThat(exec).contains("BP_HOME");
        assertThat(plan.exercise().path("rationale").asText()).isNotBlank();
        assertThat(plan.contextSnapshot().path("rationale").path("overview").asText()).isNotBlank();
    }

    @Test
    void obesityAddsWeightLog() {
        CarePlanContext ctx = ctx(List.of("肥胖"));
        var plan = registry.generate(CarePlanTemplateKeyEnum.OBESITY, ctx, null, null);
        assertThat(plan.execution().toString()).contains("WEIGHT_LOG");
        assertThat(plan.execution().toString()).contains(MetricTaskRules.REF_WEIGHT);
    }

    @Test
    void wellnessHasNoForcedMetricTasks() {
        CarePlanContext ctx = ctx(List.of());
        var plan = registry.generate(null, ctx, null, null);
        assertThat(plan.templateKey()).isEqualTo(CarePlanTemplateKeyEnum.WELLNESS);
        assertThat(plan.execution().toString()).doesNotContain("GLUCOSE_CHECK");
        assertThat(plan.execution().toString()).doesNotContain("BP_HOME");
        assertThat(plan.specialtyMismatch()).isFalse();
    }

    @Test
    void resolveLegacyOverride() {
        assertThat(registry.resolveTemplateKey(List.of(), "GENERAL"))
                .isEqualTo(CarePlanTemplateKeyEnum.WELLNESS);
        assertThat(registry.resolveTemplateKey(List.of("DIABETES", "HYPERTENSION"), null))
                .isEqualTo(CarePlanTemplateKeyEnum.COMPREHENSIVE);
    }

    @Test
    void templateOutputPassesSeedGuidelinesWithRefsOnEveryBlock() {
        GuidelineLibrary library = new GuidelineLibrary(true);
        for (List<String> diseases : List.of(
                List.<String>of(),
                List.of("HYPERTENSION"),
                List.of("DIABETES"),
                List.of("OBESITY"),
                List.of("DIABETES", "HYPERTENSION", "OBESITY"))) {
            for (boolean hypo : List.of(false, true)) {
                Set<String> tags = new LinkedHashSet<>(CarePlanDiseaseTags.detect(diseases).tags());
                GuidelineMatch match = GuidelineMatcher.match(
                        library, GuidelineFacts.basic(tags, hypo, false, null, null, null, Map.of()));
                CarePlanContext ctx = ctx(diseases, hypo, match);
                var plan = registry.generate(registry.resolveTemplateKey(diseases, null), ctx, null, null);
                var flags = GuidelineCheck.apply(plan.exercise(), plan.diet(), plan.execution(), match, true);
                assertThat(flags)
                        .as("diseases=%s hypo=%s", diseases, hypo)
                        .isEmpty();
                assertThat(plan.exercise().path("refs").isArray()).isTrue();
                assertThat(plan.diet().path("refs").isArray()).isTrue();
            }
        }
    }

    private static CarePlanContext ctx(List<String> diseases) {
        return ctx(diseases, false, GuidelineMatch.DISABLED);
    }

    private static CarePlanContext ctx(List<String> diseases, boolean hypo, GuidelineMatch match) {
        return new CarePlanContext(
                diseases,
                List.of(),
                AllergyStatusEnum.NKA,
                List.of(),
                List.of(),
                List.of(),
                false,
                hypo,
                JsonUtils.emptyObject(),
                match);
    }
}
