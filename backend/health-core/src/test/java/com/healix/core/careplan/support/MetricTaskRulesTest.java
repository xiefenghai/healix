package com.healix.core.careplan.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MetricTaskRulesTest {

    @Test
    void taskMatchesByRelatedRefEvenWithAgentCode() {
        assertThat(MetricTaskRules.metricRefOfTask("blood_glucose", "MONITOR_GLU_1")).isEqualTo("BLOOD_GLUCOSE");
        assertThat(MetricTaskRules.metricRefOfTask("BLOOD_PRESSURE", null)).isEqualTo("BLOOD_PRESSURE");
        assertThat(MetricTaskRules.metricRefOfTask("BLOOD_PRESSURE_SYS", null)).isEqualTo("BLOOD_PRESSURE");
    }

    @Test
    void taskFallsBackToTemplateCode() {
        assertThat(MetricTaskRules.metricRefOfTask(null, "GLUCOSE_CHECK")).isEqualTo("BLOOD_GLUCOSE");
        assertThat(MetricTaskRules.metricRefOfTask("", "bp_home")).isEqualTo("BLOOD_PRESSURE");
    }

    @Test
    void nonMetricTasksAreIgnored() {
        assertThat(MetricTaskRules.metricRefOfTask("WALKING", "WALK_DAILY")).isNull();
        assertThat(MetricTaskRules.metricRefOfTask(null, "WATER_LOG")).isNull();
    }

    @Test
    void metricTypesMapToTasks() {
        assertThat(MetricTaskRules.metricRefOfMetricType("BLOOD_GLUCOSE")).isEqualTo("BLOOD_GLUCOSE");
        assertThat(MetricTaskRules.metricRefOfMetricType("BLOOD_PRESSURE_DIA")).isEqualTo("BLOOD_PRESSURE");
        assertThat(MetricTaskRules.metricRefOfMetricType("WEIGHT")).isNull();
    }
}
