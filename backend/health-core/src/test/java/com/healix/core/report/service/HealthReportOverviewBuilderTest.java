package com.healix.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HealthReportOverviewBuilderTest {

    private final HealthReportOverviewBuilder builder = new HealthReportOverviewBuilder();

    @Test
    void kpisCarryPreviousPeriodTrend() {
        Map<String, Object> root = root(0.86, 0.6, 14, 0);
        builder.apply(root, "WEEK");

        Map<String, Object> plan = kpi(root, "plan");
        assertThat(plan.get("value")).isEqualTo(86d);
        assertThat(plan.get("delta")).isEqualTo(26d);
        assertThat(plan.get("trend")).isEqualTo("BETTER");
        assertThat(plan.get("deltaText")).isEqualTo("较上期 +26%");
    }

    @Test
    void weakTaskAndAbnormalMetricBecomeWatchAndFocus() {
        Map<String, Object> root = root(0.5, null, 3, 4);
        builder.apply(root, "WEEK");

        List<String> watch = texts(root, "WATCH");
        List<String> focus = texts(root, "FOCUS");
        assertThat(watch).anyMatch(t -> t.contains("血压有 4 次超出参考范围"));
        assertThat(watch).anyMatch(t -> t.contains("「晚餐后散步」只完成了 1/7 次"));
        assertThat(focus).anyMatch(t -> t.contains("早晚各测一次血压"));
        assertThat(focus).anyMatch(t -> t.contains("「晚餐后散步」"));
    }

    @Test
    void streakBecomesMilestone() {
        Map<String, Object> root = root(1.0, 0.9, 7, 0);
        builder.apply(root, "MONTH");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> milestones = (List<Map<String, Object>>) root.get("milestones");
        assertThat(milestones).extracting(m -> m.get("text")).contains("连续 7 天完成全部方案任务");
    }

    @Test
    void weeklyBucketsSplitBySevenDaysWithPartialTail() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        List<Map<String, Object>> daily = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            daily.add(Map.of("date", start.plusDays(i).toString(), "due", 2, "done", 1));
        }
        List<Map<String, Object>> weeks = HealthReportContentBuilder.weeklyBuckets(
                start, start.plusDays(29), daily, List.of("due", "done"), "done", "due");

        assertThat(weeks).hasSize(5);
        assertThat(weeks.get(0).get("due")).isEqualTo(14L);
        assertThat(weeks.get(4).get("weekEnd")).isEqualTo("2026-09-30");
        assertThat(weeks.get(4).get("due")).isEqualTo(4L);
        assertThat(weeks.get(4).get("rate")).isEqualTo(0.5);
    }

    @Test
    void previousWindowMatchesPeriodType() {
        LocalDate[] month = HealthReportContentBuilder.previousWindow(
                "MONTH", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
        assertThat(month).containsExactly(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

        LocalDate[] week = HealthReportContentBuilder.previousWindow(
                "WEEK", LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 16));
        assertThat(week).containsExactly(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 9));
    }

    private static Map<String, Object> root(Double planRate, Double prevPlanRate, int streak, int bpAbnormal) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("period", Map.of("dayCount", 7));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("dueCount", 14);
        plan.put("doneCount", (int) Math.round(planRate * 14));
        plan.put("rate", planRate);
        plan.put("longestStreak", streak);
        Map<String, Object> prev = new LinkedHashMap<>();
        prev.put("rate", prevPlanRate);
        plan.put("prev", prev);
        plan.put("byTask", List.of(Map.of("title", "晚餐后散步", "due", 7, "done", 1, "rate", 0.14)));
        root.put("adherence", Map.of("plan", plan, "med", Map.of("dueDayCount", 0)));

        Map<String, Object> bp = new LinkedHashMap<>();
        bp.put("family", "BP");
        bp.put("label", "血压");
        bp.put("count", 6);
        bp.put("abnormalCount", bpAbnormal);
        bp.put("inRangeRate", (6d - bpAbnormal) / 6d);
        bp.put("abnormalItems", List.of(Map.of("text", "血压 150/95 mmHg（家庭）")));
        bp.put("stats", Map.of("sysAvg", 135));
        root.put("metrics", List.of(bp));

        root.put("lifestyle", Map.of(
                "dayCount", 7,
                "diet", Map.of("recordDays", 0, "sodiumLimitMg", 2000),
                "exercise", Map.of("activeDays", 0)));
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> kpi(Map<String, Object> root, String key) {
        List<Map<String, Object>> kpis =
                (List<Map<String, Object>>) ((Map<String, Object>) root.get("overview")).get("kpis");
        return kpis.stream().filter(k -> key.equals(k.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<String> texts(Map<String, Object> root, String type) {
        List<Map<String, Object>> hl =
                (List<Map<String, Object>>) ((Map<String, Object>) root.get("overview")).get("highlights");
        return hl.stream().filter(h -> type.equals(h.get("type"))).map(h -> String.valueOf(h.get("text"))).toList();
    }
}
