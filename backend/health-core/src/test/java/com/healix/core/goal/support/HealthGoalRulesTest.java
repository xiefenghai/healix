package com.healix.core.goal.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class HealthGoalRulesTest {

    @Test
    void defaultModeByBmi() {
        assertThat(HealthGoalRules.defaultMode(28.0, false)).isEqualTo("LOSS");
        assertThat(HealthGoalRules.defaultMode(22.0, false)).isEqualTo("BODY_COMPOSITION");
        assertThat(HealthGoalRules.defaultMode(17.0, false)).isEqualTo("MAINTAIN");
        assertThat(HealthGoalRules.defaultMode(28.0, true)).isEqualTo("MAINTAIN");
    }

    @Test
    void underweightLossIsError() {
        var d = HealthGoalRules.validate("LOSS", 17.0, false, null, 165.0);
        assertThat(d.issues()).anyMatch(i -> "ERROR".equals(i.level()) && i.code().contains("UNDERWEIGHT"));
    }

    @Test
    void blockHighlightRejectsLoss() {
        assertThatThrownBy(() -> HealthGoalRules.requireValid("LOSS", 28.0, true, null, 165.0))
                .hasMessageContaining("阻断");
    }

    @Test
    void targetBmiTooLowIsError() {
        ObjectNode goals = JsonUtils.emptyObject();
        ObjectNode w = goals.putObject("WEIGHT");
        w.put("metric", "WEIGHT");
        w.put("baseline", 55);
        w.put("target", 40);
        var d = HealthGoalRules.validate("LOSS", 22.0, false, goals, 165.0);
        assertThat(d.issues()).anyMatch(i -> "GOAL_TARGET_BMI_LOW".equals(i.code()));
    }

    @Test
    void fastPaceIsWarn() {
        ObjectNode goals = JsonUtils.emptyObject();
        ObjectNode w = goals.putObject("WEIGHT");
        w.put("metric", "WEIGHT");
        w.put("baseline", 80);
        w.put("target", 70);
        w.put("deadline", java.time.LocalDate.now().plusWeeks(4).toString());
        var d = HealthGoalRules.validate("LOSS", 28.0, false, goals, 170.0);
        assertThat(d.issues()).anyMatch(i -> "GOAL_PACE_FAST".equals(i.code()));
    }
}
