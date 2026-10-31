package com.healix.core.careplan.guideline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.careplan.dto.SafetyFlagDto;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GuidelineCheckTest {

    private static final GuidelineLibrary SEED = new GuidelineLibrary(true);

    @Test
    void springPicksTheConfigConstructor() {
        long autowired = java.util.Arrays.stream(GuidelineLibrary.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .count();
        assertThat(autowired).isEqualTo(1);
    }

    @Test
    void productionWithoutReviewedRecsIsDisabled() {
        GuidelineLibrary prod = new GuidelineLibrary(false);
        assertThat(prod.enabled()).isFalse();
        GuidelineMatch match = GuidelineMatcher.match(prod, GuidelineFacts.of(Set.of("DIABETES")));
        assertThat(match.enabled()).isFalse();
        assertThat(GuidelineCheck.apply(exercise(60, "VIGOROUS", 7), diet(), execution(), match, true)).isEmpty();
    }

    @Test
    void hypoglycemiaRiskExcludesAerobicTargetAndAddsSafetyRecs() {
        GuidelineMatch match = match(Set.of("DIABETES"), true);
        assertThat(match.matchedCodes()).contains("DM.EX.HYPO_SESSION", "DM.EX.HYPO_INTENSITY");
        assertThat(match.matchedCodes()).doesNotContain("GEN.PA.AEROBIC", "DM.EX.POSTMEAL");
        assertThat(match.excluded("DM.EX.POSTMEAL")).isPresent();
    }

    @Test
    void safetyViolationIsErrorEvenWithoutRefs() {
        GuidelineMatch match = match(Set.of("DIABETES"), true);
        List<SafetyFlagDto> flags =
                GuidelineCheck.apply(exercise(40, "MODERATE", 5), diet(), execution("BLOOD_GLUCOSE"), match, false);
        SafetyFlagDto safety = flag(flags, GuidelineCheck.ERR_SAFETY);
        assertThat(safety.level()).isEqualTo("ERROR");
        assertThat(safety.message()).contains("40 分钟").contains("中等强度");
    }

    @Test
    void targetShortfallIsWarnWithMinutes() {
        GuidelineMatch match = match(Set.of("HYPERTENSION"), false);
        List<SafetyFlagDto> flags =
                GuidelineCheck.apply(exercise(20, "MODERATE", 3), diet(), execution("BLOOD_PRESSURE"), match, false);
        SafetyFlagDto target = flag(flags, GuidelineCheck.WARN_TARGET);
        assertThat(target.level()).isEqualTo("WARN");
        assertThat(target.message()).contains("每周约 60 分钟");
        assertThat(flags).noneMatch(f -> f.code().equals(GuidelineCheck.ERR_SAFETY));
    }

    @Test
    void vigorousCountsDoubleTowardsWeeklyTarget() {
        assertThat(GuidelineCheck.weeklyModerateMinutes(exercise(25, "VIGOROUS", 3))).isEqualTo(150);
        assertThat(GuidelineCheck.weeklyModerateMinutes(exercise(60, "LIGHT", 7))).isZero();
    }

    @Test
    void missingMonitorTaskIsTargetConflict() {
        GuidelineMatch match = match(Set.of("HYPERTENSION"), false);
        List<SafetyFlagDto> flags = GuidelineCheck.apply(exercise(30, "MODERATE", 7), diet(), execution(), match, false);
        assertThat(flag(flags, GuidelineCheck.WARN_TARGET).message()).contains("BLOOD_PRESSURE");
    }

    @Test
    void unknownRefsAreRemovedAndExcludedRefsAreErrors() {
        GuidelineMatch match = match(Set.of("DIABETES"), true);
        ObjectNode ex = exercise(20, "LIGHT", 7);
        ex.putArray("refs").add("DM.EX.HYPO_SESSION").add("MADE.UP").add("DM.EX.POSTMEAL");
        List<SafetyFlagDto> flags = GuidelineCheck.apply(ex, diet(), execution("BLOOD_GLUCOSE"), match, true);
        assertThat(GuidelineCheck.refsOf(ex)).containsExactly("DM.EX.HYPO_SESSION", "DM.EX.POSTMEAL");
        assertThat(flag(flags, GuidelineCheck.WARN_REF_UNKNOWN).message()).contains("MADE.UP");
        assertThat(flag(flags, GuidelineCheck.ERR_REF_EXCLUDED).message()).contains("DM.EX.POSTMEAL").contains("低血糖");
    }

    @Test
    void missingRefsOnlyWhenRequired() {
        GuidelineMatch match = match(Set.of(), false);
        var ex = exercise(30, "MODERATE", 7);
        assertThat(GuidelineCheck.apply(ex, diet(), execution(), match, false))
                .noneMatch(f -> f.code().equals(GuidelineCheck.WARN_REF_MISSING));
        assertThat(flag(GuidelineCheck.apply(ex, diet(), execution(), match, true), GuidelineCheck.WARN_REF_MISSING)
                        .message())
                .contains("运动方案").contains("饮食方案");
    }

    @Test
    void conditionsCoverBmiAgeAndAssessmentTier() {
        GuidelineCondition c = GuidelineCondition.parse(
                JsonUtils.readTree("{\"bmiMin\":24,\"ageMin\":60,\"assessmentTierAtLeast\":{\"HYPERTENSION\":\"HIGH\"}}"),
                "t");
        GuidelineFacts ok = GuidelineFacts.basic(
                Set.of("HYPERTENSION"), false, false, 26.0, 65, "MALE", Map.of("HYPERTENSION", AssessmentTier.VERY_HIGH));
        assertThat(c.matches(ok)).isTrue();
        assertThat(c.matches(GuidelineFacts.basic(Set.of(), false, false, null, 65, null, Map.of()))).isFalse();
        assertThat(c.describe()).contains("BMI ≥24").contains("年龄 ≥60").contains("高血压评估等级≥高");
    }

    @Test
    void libraryRejectsUnreviewedReviewedRecAndUnknownKeys() {
        String base = "{\"version\":\"t\",\"sources\":[{\"code\":\"S\",\"name\":\"n\"}],\"recs\":[%s]}";
        assertThatThrownBy(() -> GuidelineLibrary.parse(JsonUtils.readTree(base.formatted(
                        "{\"code\":\"A\",\"sourceCode\":\"S\",\"kind\":\"TARGET\",\"block\":\"DIET\",\"status\":\"REVIEWED\",\"staffText\":\"x\"}"))))
                .hasMessageContaining("审定人");
        assertThatThrownBy(() -> GuidelineLibrary.parse(JsonUtils.readTree(base.formatted(
                        "{\"code\":\"A\",\"sourceCode\":\"S\",\"kind\":\"TARGET\",\"block\":\"DIET\",\"status\":\"DRAFT\",\"staffText\":\"x\",\"appliesWhen\":{\"medClass\":\"X\"}}"))))
                .hasMessageContaining("medClass");
        assertThatThrownBy(() -> GuidelineLibrary.parse(JsonUtils.readTree(base.formatted(
                        "{\"code\":\"A\",\"sourceCode\":\"NOPE\",\"kind\":\"TARGET\",\"block\":\"DIET\",\"status\":\"DRAFT\",\"staffText\":\"x\"}"))))
                .hasMessageContaining("来源不存在");
    }

    private static GuidelineMatch match(Set<String> tags, boolean hypo) {
        return GuidelineMatcher.match(SEED, GuidelineFacts.basic(tags, hypo, false, null, null, null, Map.of()));
    }

    private static SafetyFlagDto flag(List<SafetyFlagDto> flags, String code) {
        return flags.stream().filter(f -> f.code().equals(code)).findFirst().orElseThrow(
                () -> new AssertionError("missing " + code + " in " + flags));
    }

    private static ObjectNode exercise(int minutes, String intensity, int days) {
        ObjectNode ex = JsonUtils.emptyObject();
        ex.put("goal", "g");
        ArrayNode weekly = ex.putArray("weeklyPlan");
        List<String> all = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
        for (int i = 0; i < days; i++) {
            ObjectNode d = weekly.addObject();
            d.put("day", all.get(i));
            d.putArray("items").addObject().put("type", "WALKING").put("durationMin", minutes).put("intensity", intensity);
        }
        return ex;
    }

    private static ObjectNode diet() {
        ObjectNode d = JsonUtils.emptyObject();
        d.putArray("principles").add("均衡");
        return d;
    }

    private static JsonNode execution(String... relatedRefs) {
        ObjectNode e = JsonUtils.emptyObject();
        ArrayNode tasks = e.putArray("tasks");
        tasks.addObject().put("code", "DIET_LOG").put("title", "记录").put("enabled", true);
        for (String ref : relatedRefs) {
            tasks.addObject().put("code", "M_" + ref).put("title", "监测").put("relatedRef", ref).put("enabled", true);
        }
        return e;
    }
}
