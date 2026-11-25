package com.healix.core.careplan.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.guideline.GuidelineMatch;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanContextService.MetricBrief;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarePlanPhaseGoalsResolverTest {

    private final CarePlanPhaseGoalsResolver resolver = new CarePlanPhaseGoalsResolver();

    @Test
    void obesityUsesIntakeWeightTarget() {
        ObjectNode snap = JsonUtils.emptyObject();
        ObjectNode goals = snap.putObject("goals");
        goals.putArray("items")
                .addObject()
                .put("metric", "WEIGHT")
                .put("target", 72)
                .put("baseline", 80)
                .put("horizon", "M3");
        CarePlanContext ctx = ctx(List.of("OBESITY"), List.of(metric("WEIGHT", "80"), metric("HEIGHT", "170")), snap);

        ObjectNode phase = resolver.resolve(CarePlanTemplateKeyEnum.OBESITY, ctx);

        assertThat(phase.path("primaryMetric").asText()).isEqualTo("WEIGHT");
        assertThat(phase.path("horizonDays").asInt()).isEqualTo(90);
        assertThat(phase.path("items").get(0).path("target").asDouble()).isEqualTo(72.0);
        assertThat(CarePlanPhaseGoalsSupport.deriveGoalSummary(phase)).contains("72");
    }

    @Test
    void hypertensionDefaultsBpRange() {
        CarePlanContext ctx = ctx(List.of("HYPERTENSION"), List.of(), JsonUtils.emptyObject());
        ObjectNode phase = resolver.resolve(CarePlanTemplateKeyEnum.HYPERTENSION, ctx);
        assertThat(phase.path("primaryMetric").asText()).isEqualTo("BP");
        assertThat(phase.path("items").get(0).path("sysMax").asInt()).isEqualTo(130);
        assertThat(phase.path("items").get(0).path("diaMax").asInt()).isEqualTo(80);
    }

    @Test
    void diabetesPrimaryFpg() {
        CarePlanContext ctx = ctx(List.of("DIABETES"), List.of(), JsonUtils.emptyObject());
        ObjectNode phase = resolver.resolve(CarePlanTemplateKeyEnum.DIABETES, ctx);
        assertThat(phase.path("primaryMetric").asText()).isEqualTo("FPG");
        assertThat(phase.path("items").get(0).path("max").asDouble()).isEqualTo(7.0);
    }

    @Test
    void comprehensivePicksWeightFromMode() {
        ObjectNode snap = JsonUtils.emptyObject();
        snap.putObject("goals").put("mode", "LOSS");
        CarePlanContext ctx =
                ctx(List.of("DIABETES", "HYPERTENSION", "OBESITY"), List.of(metric("WEIGHT", "88"), metric("HEIGHT", "172")), snap);
        ObjectNode phase = resolver.resolve(CarePlanTemplateKeyEnum.COMPREHENSIVE, ctx);
        assertThat(phase.path("primaryMetric").asText()).isEqualTo("WEIGHT");
        assertThat(phase.path("items").size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void wellnessUsesMaintainBandWhenHeightWeightPresent() {
        CarePlanContext ctx =
                ctx(List.of(), List.of(metric("WEIGHT", "62"), metric("HEIGHT", "168")), JsonUtils.emptyObject());
        ObjectNode phase = resolver.resolve(CarePlanTemplateKeyEnum.WELLNESS, ctx);
        assertThat(phase.path("primaryMetric").asText()).isEqualTo("WEIGHT");
        assertThat(phase.path("items").get(0).path("display").asText()).isEqualTo("RANGE");
    }

    private static CarePlanContext ctx(List<String> diseases, List<MetricBrief> metrics, ObjectNode snap) {
        return new CarePlanContext(
                diseases,
                List.of(),
                AllergyStatusEnum.UNKNOWN,
                List.of(),
                metrics,
                List.of(),
                false,
                false,
                snap,
                GuidelineMatch.DISABLED);
    }

    private static MetricBrief metric(String type, String value) {
        return new MetricBrief(type, value, null, type);
    }
}
