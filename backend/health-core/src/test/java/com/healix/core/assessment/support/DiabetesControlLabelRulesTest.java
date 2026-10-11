package com.healix.core.assessment.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.assessment.support.DiabetesControlLabelRules.Input;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Label;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Metric;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Output;
import com.healix.core.assessment.support.DiabetesControlLabelRules.TargetGroup;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DiabetesControlLabelRulesTest {

    @Test
    void noneWhenNoMetrics() {
        Output out = DiabetesControlLabelRules.evaluate(base(45, 0, false)
                .a1c(Metric.missing())
                .fbg(Metric.missing())
                .pbg(Metric.missing())
                .build());
        assertEquals(Label.NONE, out.label());
        assertEquals("RESTORE_DATA", out.adviceCode());
    }

    @Test
    void redWhenHypoTwice() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .fbg(Metric.of(bd("6.5")))
                .hypo14dCount(2)
                .build());
        assertEquals(Label.RED, out.label());
        assertTrue(out.redHits().contains("HYPO_14D"));
    }

    @Test
    void redWhenA1cGe9() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .a1c(Metric.of(bd("9.0")))
                .build());
        assertEquals(Label.RED, out.label());
        assertTrue(out.redHits().contains("A1C_GE_9"));
    }

    @Test
    void redWhenFbgAndPbgBothHigh() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .fbg(Metric.of(bd("10.0")))
                .pbg(Metric.of(bd("13.9")))
                .build());
        assertEquals(Label.RED, out.label());
        assertTrue(out.redHits().contains("FBG_PBG_BOTH_HIGH"));
    }

    @Test
    void notRedWhenOnlyFbgHigh() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .fbg(Metric.of(bd("11.0")))
                .build());
        assertEquals(Label.YELLOW, out.label());
    }

    @Test
    void yellowWhenOverTarget() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .a1c(Metric.of(bd("7.2")))
                .build());
        assertEquals(Label.YELLOW, out.label());
        assertEquals(TargetGroup.GROUP_1, out.targetGroup());
    }

    @Test
    void greenWhenYoungAndAllMeet() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .a1c(Metric.of(bd("6.5")))
                .fbg(Metric.of(bd("6.2")))
                .pbg(Metric.of(bd("8.0")))
                .build());
        assertEquals(Label.GREEN, out.label());
    }

    @Test
    void nearGreenWhenOnlyFbgOk() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .fbg(Metric.of(bd("6.5")))
                .build());
        assertEquals(Label.NEAR_GREEN, out.label());
    }

    @Test
    void group3WhenElderlyWithComorbidities() {
        Output out = DiabetesControlLabelRules.evaluate(base(70, 3, false)
                .a1c(Metric.of(bd("8.2")))
                .build());
        assertEquals(TargetGroup.GROUP_3, out.targetGroup());
        assertEquals(Label.GREEN, out.label());
    }

    @Test
    void group3WhenEndStage() {
        Output out = DiabetesControlLabelRules.evaluate(base(70, 0, true)
                .a1c(Metric.of(bd("8.4")))
                .build());
        assertEquals(TargetGroup.GROUP_3, out.targetGroup());
        assertEquals(Label.GREEN, out.label());
    }

    @Test
    void group2ElderlyFewComorbidities() {
        Output out = DiabetesControlLabelRules.evaluate(base(68, 1, false)
                .a1c(Metric.of(bd("7.5")))
                .build());
        assertEquals(TargetGroup.GROUP_2, out.targetGroup());
        assertEquals(Label.GREEN, out.label());
    }

    @Test
    void archiveHypoFallbackCountsAsRed() {
        Output out = DiabetesControlLabelRules.evaluate(base(50, 0, false)
                .fbg(Metric.of(bd("6.0")))
                .hypoArchiveFallbackHit(true)
                .build());
        assertEquals(Label.RED, out.label());
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static InputBuilder base(int age, int comorbidity, boolean endStage) {
        return new InputBuilder(age, comorbidity, endStage);
    }

    private static final class InputBuilder {
        private final Integer age;
        private final int comorbidity;
        private final boolean endStage;
        private Metric a1c = Metric.missing();
        private Metric fbg = Metric.missing();
        private Metric pbg = Metric.missing();
        private int hypo14dCount;
        private boolean hypoArchiveFallbackHit;

        private InputBuilder(Integer age, int comorbidity, boolean endStage) {
            this.age = age;
            this.comorbidity = comorbidity;
            this.endStage = endStage;
        }

        InputBuilder a1c(Metric m) {
            this.a1c = m;
            return this;
        }

        InputBuilder fbg(Metric m) {
            this.fbg = m;
            return this;
        }

        InputBuilder pbg(Metric m) {
            this.pbg = m;
            return this;
        }

        InputBuilder hypo14dCount(int n) {
            this.hypo14dCount = n;
            return this;
        }

        InputBuilder hypoArchiveFallbackHit(boolean v) {
            this.hypoArchiveFallbackHit = v;
            return this;
        }

        Input build() {
            return new Input(age, comorbidity, endStage, a1c, fbg, pbg, hypo14dCount, hypoArchiveFallbackHit);
        }
    }
}
