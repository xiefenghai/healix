package com.healix.core.reward.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.healix.core.reward.dto.RewardSourceEvent;
import com.healix.core.reward.service.RewardRules.DayResult;
import com.healix.core.reward.service.RewardRules.Entry;
import com.healix.core.reward.service.RewardRules.StreakStep;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RewardRulesTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 10);

    @Test
    void eachActionEarnsFiveAndPlanMedVitalMakeActiveDay() {
        DayResult r = RewardRules.evaluate(D, List.of(
                ev("PLAN", "p1", D.atTime(8, 0)),
                ev("MED", "m1", D.atTime(8, 5)),
                ev("DIET", "d1", D.atTime(12, 0))));
        assertThat(r.total()).isEqualTo(15);
        assertThat(r.active()).isTrue();
        assertThat(r.entries()).extracting(Entry::bizRef).containsExactly("p1", "m1", "d1");
    }

    @Test
    void dietAndExerciseAloneDoNotMakeActiveDay() {
        DayResult r = RewardRules.evaluate(D, List.of(
                ev("DIET", "d1", D.atTime(12, 0)), ev("EXERCISE", "e1", D.atTime(18, 0))));
        assertThat(r.active()).isFalse();
        assertThat(r.total()).isEqualTo(10);
    }

    @Test
    void perTypeCaps() {
        List<RewardSourceEvent> events = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            events.add(ev("DIET", "d" + i, D.atTime(8 + i, 0)));
            events.add(ev("EXERCISE", "e" + i, D.atTime(8 + i, 1)));
        }
        DayResult r = RewardRules.evaluate(D, events);
        assertThat(r.entries().stream().filter(e -> e.bizType().equals("DIET"))).hasSize(3);
        assertThat(r.entries().stream().filter(e -> e.bizType().equals("EXERCISE"))).hasSize(1);
    }

    @Test
    void dailyCapIsEighty() {
        List<RewardSourceEvent> events = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            events.add(ev("PLAN", String.format("p%02d", i), D.atTime(6, i)));
        }
        DayResult r = RewardRules.evaluate(D, events);
        assertThat(r.total()).isEqualTo(80);
        assertThat(r.entries()).hasSize(16);
        assertThat(r.entries().get(0).bizRef()).isEqualTo("p00");
    }

    @Test
    void lateEntryAfterNextDayIsIgnored() {
        DayResult r = RewardRules.evaluate(D, List.of(
                ev("PLAN", "ok", D.plusDays(1).atTime(23, 59)),
                ev("PLAN", "late", D.plusDays(2).atTime(0, 0))));
        assertThat(r.entries()).extracting(Entry::bizRef).containsExactly("ok");
    }

    @Test
    void bloodPressurePairCountsOnceAndDedupesWithinTenMinutes() {
        LocalDateTime t = D.atTime(7, 30);
        DayResult r = RewardRules.evaluate(D, List.of(
                vital("s1", null, "BLOOD_PRESSURE_SYS", t, t),
                vital("d1", null, "BLOOD_PRESSURE_DIA", t, t),
                vital("s2", null, "BLOOD_PRESSURE_SYS", t.plusMinutes(5), t.plusMinutes(5)),
                vital("g1", "G", "BLOOD_GLUCOSE", t.plusMinutes(6), t.plusMinutes(6)),
                vital("s3", null, "BLOOD_PRESSURE_SYS", t.plusMinutes(40), t.plusMinutes(40))));
        assertThat(r.entries()).extracting(Entry::bizRef).containsExactly("d1", "G", "s3");
        assertThat(r.entries().get(0).title()).isEqualTo("测量 · 血压");
    }

    @Test
    void vitalEnteredMoreThan24hAfterMeasurementIsIgnored() {
        LocalDateTime t = D.atTime(7, 0);
        DayResult r = RewardRules.evaluate(D, List.of(vital("v", null, "WEIGHT", t, t.plusHours(25))));
        assertThat(r.entries()).isEmpty();
        assertThat(r.active()).isFalse();
    }

    @Test
    void streakStepGrantsFreezeEverySevenAndConsumesIt() {
        StreakStep s = RewardRules.step(6, 0, true);
        assertThat(s.streak()).isEqualTo(7);
        assertThat(s.freezeCards()).isEqualTo(1);
        assertThat(s.freezeGained()).isTrue();

        StreakStep miss = RewardRules.step(7, 1, false);
        assertThat(miss.streak()).isEqualTo(7);
        assertThat(miss.freezeCards()).isZero();
        assertThat(miss.freezeUsed()).isTrue();

        assertThat(RewardRules.step(7, 0, false).streak()).isZero();
        assertThat(RewardRules.step(13, 2, true).freezeCards()).isEqualTo(2);
    }

    @Test
    void provisionalStreakKeepsFlameUntilDayIsFinal() {
        LocalDate today = D;
        LocalDate settled = today.minusDays(2);
        assertThat(RewardRules.provisionalStreak(5, 0, settled, Set.of(), today)).isEqualTo(5);
        assertThat(RewardRules.provisionalStreak(5, 0, settled, Set.of(today), today)).isEqualTo(6);
        assertThat(RewardRules.provisionalStreak(5, 0, settled, Set.of(today.minusDays(1), today), today))
                .isEqualTo(7);
        // 任务延迟一天：D-2 未结算且未打卡 → 无卡归零
        assertThat(RewardRules.provisionalStreak(5, 0, today.minusDays(3), Set.of(today), today)).isEqualTo(1);
        assertThat(RewardRules.provisionalStreak(5, 1, today.minusDays(3), Set.of(today), today)).isEqualTo(6);
    }

    @Test
    void trailingStreakCountsBackFromEnd() {
        LocalDate end = D;
        List<LocalDate> days = List.of(
                end.plusDays(1), end, end.minusDays(1), end.minusDays(2), end.minusDays(4));
        assertThat(RewardRules.trailingStreak(days, end)).isEqualTo(3);
        assertThat(RewardRules.trailingStreak(List.of(end.minusDays(1)), end)).isZero();
    }

    @Test
    void reportReadEarnsTenButIsNotActive() {
        DayResult r = RewardRules.evaluate(D, List.of(ev("REPORT_READ", "r1", D.atTime(20, 0))));
        assertThat(r.total()).isEqualTo(10);
        assertThat(r.active()).isFalse();
    }

    @Test
    void implausibleVitalGroupEarnsNothing() {
        RewardSourceEvent sys = vital("v1", "g1", "BLOOD_PRESSURE_SYS", D.atTime(8, 0), D.atTime(8, 0));
        sys.setMetricValue(new BigDecimal("400"));
        RewardSourceEvent dia = vital("v2", "g1", "BLOOD_PRESSURE_DIA", D.atTime(8, 0), D.atTime(8, 0));
        dia.setMetricValue(new BigDecimal("85"));
        RewardSourceEvent glu = vital("v3", null, "BLOOD_GLUCOSE", D.atTime(9, 0), D.atTime(9, 0));
        glu.setMetricValue(new BigDecimal("6.2"));
        DayResult r = RewardRules.evaluate(D, List.of(sys, dia, glu));
        assertThat(r.entries()).extracting(Entry::bizRef).containsExactly("v3");
    }

    private static RewardSourceEvent ev(String type, String ref, LocalDateTime created) {
        return new RewardSourceEvent(type, ref, created, null);
    }

    private static RewardSourceEvent vital(
            String ref, String group, String metric, LocalDateTime at, LocalDateTime created) {
        RewardSourceEvent e = new RewardSourceEvent("VITAL", ref, created, null);
        e.setGroupId(group);
        e.setSub(metric);
        e.setOccurredAt(at);
        return e;
    }
}
