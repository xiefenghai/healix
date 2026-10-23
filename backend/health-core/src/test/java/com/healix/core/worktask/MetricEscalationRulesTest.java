package com.healix.core.worktask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.worktask.support.MetricEscalationRules;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricEscalationRulesTest {

    @Test
    void singleMildReadingStaysOnAlert() {
        assertFalse(MetricEscalationRules.shouldEscalate(List.of(bp(150, 96))));
        assertFalse(MetricEscalationRules.shouldEscalate(List.of(glucose(8.2))));
        assertFalse(MetricEscalationRules.shouldEscalate(List.of(Map.of("family", "BMI", "bmi", 28.4))));
        assertNull(MetricEscalationRules.escalateReason(List.of(bp(150, 96))));
    }

    @Test
    void repeatFamilyOrDangerousReadingEscalates() {
        assertTrue(MetricEscalationRules.shouldEscalate(List.of(bp(146, 92), bp(150, 94))));
        assertEquals("同一指标再次异常", MetricEscalationRules.escalateReason(List.of(bp(146, 92), bp(150, 94))));
        assertTrue(MetricEscalationRules.shouldEscalate(List.of(bp(182, 100))));
        assertEquals("危险值需优先处理", MetricEscalationRules.escalateReason(List.of(bp(182, 100))));
        assertTrue(MetricEscalationRules.shouldEscalate(List.of(bp(150, 112))));
        assertTrue(MetricEscalationRules.shouldEscalate(List.of(glucose(13.9))));
        assertTrue(MetricEscalationRules.shouldEscalate(List.of(glucose(3.6))));
        assertTrue(MetricEscalationRules.shouldEscalate(List.of(
                Map.of("family", "HR", "value", 110),
                Map.of("family", "HR", "value", 112))));
        assertEquals(
                "多项指标重复异常",
                MetricEscalationRules.escalateReason(List.of(
                        Map.of("family", "HR", "value", 110),
                        Map.of("family", "HR", "value", 112),
                        Map.of("family", "BMI", "bmi", 28.4),
                        Map.of("family", "BMI", "bmi", 29.1))));
        assertEquals(
                "收缩压偏高",
                MetricEscalationRules.escalateReason(
                        List.of(Map.of("family", "BP", "sys", 182d, "dia", 100d, "summary", "收缩压偏高"))));
    }

    private static Map<String, Object> bp(double sys, double dia) {
        return Map.of("family", "BP", "sys", sys, "dia", dia);
    }

    private static Map<String, Object> glucose(double value) {
        return Map.of("family", "GLUCOSE", "value", value);
    }
}
