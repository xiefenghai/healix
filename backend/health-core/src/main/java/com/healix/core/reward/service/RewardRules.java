package com.healix.core.reward.service;

import com.healix.core.reward.catalog.RewardBizType;
import com.healix.core.reward.dto.RewardSourceEvent;
import com.healix.core.vitals.support.VitalValueRules;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 健康豆计分规则（纯函数）：源记录 → 当日应得流水。
 *
 * <ul>
 *   <li>录入时间 − 业务时间 ≤ 24h（G26）：按日期记的行为须在 D+1 结束前录入；体征按测量时间算。
 *   <li>体征：同组/同分钟为 1 次测量；同类 10 分钟内只计 1 次；每日最多 3 次。
 *   <li>饮食每日最多 3 餐，运动每日 1 次；每项 +5，阅读管理报告 +10（每份一次）；每日合计上限 80（按录入先后）。
 * </ul>
 */
public final class RewardRules {

    public static final int POINTS_PER_ACTION = 5;
    public static final int POINTS_FULL_DAY = 20;
    public static final int DAILY_CAP = 80;

    private static final Map<String, Integer> TYPE_CAPS = Map.of(
            RewardBizType.VITAL.name(), 3,
            RewardBizType.DIET.name(), 3,
            RewardBizType.EXERCISE.name(), 1);

    private static final Map<String, Integer> TYPE_POINTS = Map.of(RewardBizType.REPORT_READ.name(), 10);

    private static final Duration LATE_LIMIT = Duration.ofHours(24);
    private static final Duration VITAL_DEDUPE = Duration.ofMinutes(10);

    private RewardRules() {}

    public record Entry(String bizType, String bizRef, int points, String title) {}

    /** @param active 当日是否有方案任务 / 服药 / 测量（不受上限影响） */
    public record DayResult(List<Entry> entries, boolean active, int total) {}

    public static DayResult evaluate(LocalDate day, List<RewardSourceEvent> events) {
        List<RewardSourceEvent> valid = new ArrayList<>();
        List<RewardSourceEvent> vitals = new ArrayList<>();
        for (RewardSourceEvent e : events == null ? List.<RewardSourceEvent>of() : events) {
            if (e == null || !StringUtils.hasText(e.getBizType()) || !StringUtils.hasText(e.getRef())) {
                continue;
            }
            if (RewardBizType.VITAL.name().equals(e.getBizType())) {
                vitals.add(e);
            } else if (onTime(day, e)) {
                valid.add(e);
            }
        }
        valid.addAll(collapseVitals(vitals));

        boolean active = valid.stream()
                .anyMatch(e -> RewardBizType.names(RewardBizType.ACTIVE).contains(e.getBizType()));

        valid.sort(Comparator.comparing(
                        RewardSourceEvent::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(RewardSourceEvent::getRef));

        Map<String, Integer> used = new HashMap<>();
        List<Entry> out = new ArrayList<>();
        int total = 0;
        for (RewardSourceEvent e : valid) {
            Integer cap = TYPE_CAPS.get(e.getBizType());
            int n = used.getOrDefault(e.getBizType(), 0);
            if (cap != null && n >= cap) {
                continue;
            }
            int pts = TYPE_POINTS.getOrDefault(e.getBizType(), POINTS_PER_ACTION);
            if (total + pts > DAILY_CAP) {
                break;
            }
            used.put(e.getBizType(), n + 1);
            total += pts;
            out.add(new Entry(e.getBizType(), e.getRef(), pts, title(e)));
        }
        return new DayResult(out, active, total);
    }

    // ---------------------------------------------------------------- 火苗

    public static final int MAX_FREEZE_CARDS = 2;

    public record StreakStep(int streak, int freezeCards, boolean freezeUsed, boolean freezeGained) {}

    /** 终结算一天：打卡日 +1（每满 7 天得 1 张保护卡，最多 2 张）；非打卡日先耗保护卡，无卡归零。 */
    public static StreakStep step(int streak, int freezeCards, boolean active) {
        if (active) {
            int s = streak + 1;
            boolean gain = s % 7 == 0 && freezeCards < MAX_FREEZE_CARDS;
            return new StreakStep(s, gain ? freezeCards + 1 : freezeCards, false, gain);
        }
        if (freezeCards > 0) {
            return new StreakStep(streak, freezeCards - 1, true, false);
        }
        return new StreakStep(0, freezeCards, false, false);
    }

    /**
     * 展示用火苗：已终结算天数 + 未结算日的推演。今天、昨天尚可补，未打卡不熄；
     * 更早的未结算日（任务延迟）按终结算规则推演。
     */
    public static int provisionalStreak(
            int settledStreak, int freezeCards, LocalDate lastSettled, Set<LocalDate> activeDays, LocalDate today) {
        int streak = settledStreak;
        int freeze = freezeCards;
        LocalDate d = lastSettled == null ? today.minusDays(1) : lastSettled.plusDays(1);
        for (; !d.isAfter(today); d = d.plusDays(1)) {
            boolean active = activeDays.contains(d);
            if (active) {
                streak++;
            } else if (d.isBefore(today.minusDays(1))) {
                StreakStep s = step(streak, freeze, false);
                streak = s.streak();
                freeze = s.freezeCards();
            }
        }
        return streak;
    }

    /** 初始化：截至 {@code end} 的真实连续打卡天数（G17）。{@code daysDesc} 降序。 */
    public static int trailingStreak(List<LocalDate> daysDesc, LocalDate end) {
        int n = 0;
        LocalDate expect = end;
        for (LocalDate d : daysDesc) {
            if (d.isAfter(expect)) {
                continue;
            }
            if (!d.equals(expect)) {
                break;
            }
            n++;
            expect = expect.minusDays(1);
        }
        return n;
    }

    static boolean onTime(LocalDate day, RewardSourceEvent e) {
        if (e.getCreatedAt() == null) {
            return true;
        }
        return e.getCreatedAt().isBefore(day.plusDays(2).atStartOfDay());
    }

    static boolean plausible(RewardSourceEvent e) {
        if (e.getMetricValue() == null || e.getSub() == null) {
            return true;
        }
        return VitalValueRules.isPlausible(e.getSub(), e.getMetricValue());
    }

    /** 同组（或无组时同一分钟）合为一次测量；超 24h 补录、任一数值不合理的整组剔除；同类 10 分钟内去重。 */
    static List<RewardSourceEvent> collapseVitals(List<RewardSourceEvent> rows) {
        Map<String, List<RewardSourceEvent>> groups = new LinkedHashMap<>();
        for (RewardSourceEvent r : rows) {
            if (r.getOccurredAt() == null) {
                continue;
            }
            String key = StringUtils.hasText(r.getGroupId())
                    ? "G:" + r.getGroupId()
                    : "T:" + r.getOccurredAt().truncatedTo(ChronoUnit.MINUTES);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<RewardSourceEvent> merged = new ArrayList<>();
        for (Map.Entry<String, List<RewardSourceEvent>> g : groups.entrySet()) {
            List<RewardSourceEvent> list = g.getValue();
            if (!list.stream().allMatch(RewardRules::plausible)) {
                continue;
            }
            RewardSourceEvent first = list.stream()
                    .min(Comparator.comparing(RewardSourceEvent::getRef))
                    .orElseThrow();
            LocalDateTime occurred = list.stream()
                    .map(RewardSourceEvent::getOccurredAt)
                    .min(Comparator.naturalOrder())
                    .orElseThrow();
            LocalDateTime created = list.stream()
                    .map(RewardSourceEvent::getCreatedAt)
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (created != null && Duration.between(occurred, created).compareTo(LATE_LIMIT) > 0) {
                continue;
            }
            String family = family(list);
            RewardSourceEvent m = new RewardSourceEvent();
            m.setBizType(RewardBizType.VITAL.name());
            m.setRef(StringUtils.hasText(first.getGroupId()) ? first.getGroupId() : first.getRef());
            m.setOccurredAt(occurred);
            m.setCreatedAt(created);
            m.setSub(family);
            m.setLabel(familyLabel(family));
            merged.add(m);
        }
        merged.sort(Comparator.comparing(RewardSourceEvent::getOccurredAt).thenComparing(RewardSourceEvent::getRef));
        Map<String, LocalDateTime> lastByFamily = new HashMap<>();
        List<RewardSourceEvent> out = new ArrayList<>();
        for (RewardSourceEvent m : merged) {
            LocalDateTime last = lastByFamily.get(m.getSub());
            if (last != null && Duration.between(last, m.getOccurredAt()).compareTo(VITAL_DEDUPE) < 0) {
                continue;
            }
            lastByFamily.put(m.getSub(), m.getOccurredAt());
            out.add(m);
        }
        return out;
    }

    private static String family(List<RewardSourceEvent> list) {
        boolean bp = list.stream().anyMatch(r -> r.getSub() != null && r.getSub().startsWith("BLOOD_PRESSURE"));
        if (bp) {
            return "BP";
        }
        return list.stream()
                .map(RewardSourceEvent::getSub)
                .filter(StringUtils::hasText)
                .sorted()
                .findFirst()
                .orElse("OTHER");
    }

    private static String familyLabel(String family) {
        return switch (family) {
            case "BP" -> "血压";
            case "BLOOD_GLUCOSE" -> "血糖";
            case "HEART_RATE" -> "心率";
            case "WEIGHT" -> "体重";
            case "WAIST" -> "腰围";
            default -> "体征";
        };
    }

    static String title(RewardSourceEvent e) {
        String base = RewardBizType.valueOf(e.getBizType()).label();
        String t = StringUtils.hasText(e.getLabel()) ? base + " · " + e.getLabel().trim() : base;
        return t.length() > 60 ? t.substring(0, 60) : t;
    }
}
