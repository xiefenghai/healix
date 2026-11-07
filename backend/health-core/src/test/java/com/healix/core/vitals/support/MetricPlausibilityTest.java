package com.healix.core.vitals.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricPlausibilityTest {

    @Test
    void waist66WithOverweightBmiIsSuspect() {
        // 172cm / 82kg → BMI≈27.7，腰围 66 与体型不匹配
        String reason =
                MetricPlausibility.waistConsistent(
                        new BigDecimal("66"), new BigDecimal("172"), new BigDecimal("82"));
        assertThat(reason).contains("不匹配");
        assertThat(MetricPlausibility.isUsefulForPrompt(
                        "WAIST", new BigDecimal("66"), new BigDecimal("172"), new BigDecimal("82")))
                .isFalse();
        assertThat(MetricPlausibility.isUsefulForPrompt(
                        "WAIST", new BigDecimal("96"), new BigDecimal("172"), new BigDecimal("82")))
                .isTrue();
    }

    @Test
    void assessIntakeAnswersFlagsWaistAndTargetGoals() {
        ObjectNode answers = JsonUtils.emptyObject();
        answers.put("metric.HEIGHT", 172);
        answers.put("metric.WEIGHT", 82);
        answers.put("metric.WAIST", 66);
        answers.put("goal.targetBpDia", 35);
        answers.put("goal.targetHba1c", 9);
        answers.put("goal.targetFpg", 14);
        List<MetricPlausibility.Hit> hits = MetricPlausibility.assessIntakeAnswers(answers);
        assertThat(hits).extracting(MetricPlausibility.Hit::field)
                .contains("metric.WAIST", "goal.targetBpDia", "goal.targetHba1c", "goal.targetFpg");
    }

    @Test
    void catalogMinTargetGoalsAreAccepted() {
        ObjectNode answers = JsonUtils.emptyObject();
        answers.put("goal.targetHba1c", 4);
        answers.put("goal.targetBpDia", 40);
        answers.put("goal.targetBpSys", 80);
        answers.put("goal.targetFpg", 3);
        assertThat(MetricPlausibility.assessIntakeAnswers(answers)).isEmpty();
    }

    @Test
    void absoluteWaistOutOfPromptRangeSkipped() {
        assertThat(MetricPlausibility.isUsefulForPrompt("WAIST", new BigDecimal("40"))).isFalse();
        assertThat(MetricPlausibility.isUsefulForPrompt("WAIST", new BigDecimal("86"))).isTrue();
    }
}
