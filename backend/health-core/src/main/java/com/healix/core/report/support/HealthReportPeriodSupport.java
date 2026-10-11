package com.healix.core.report.support;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 管理报告周期窗口：周报按入组日对齐；月/季按自然月/自然季。 */
public final class HealthReportPeriodSupport {

    public record PeriodWindow(LocalDate start, LocalDate end) {}

    private HealthReportPeriodSupport() {}

    /**
     * 入组对齐周：join 起每 7 天一窗。若今天恰为某周结束后的次日（daysSinceJoin % 7 == 0），
     * 返回刚结束的那一周；否则 null（Job 用，避免周一扎堆）。
     */
    public static PeriodWindow enrollWeekJustEnded(LocalDate joinDate, LocalDate today) {
        if (joinDate == null || today == null || today.isBefore(joinDate.plusDays(7))) {
            return null;
        }
        long daysSinceJoin = ChronoUnit.DAYS.between(joinDate, today);
        if (daysSinceJoin <= 0 || daysSinceJoin % 7 != 0) {
            return null;
        }
        LocalDate start = today.minusDays(7);
        return new PeriodWindow(start, today.minusDays(1));
    }

    /** 手工默认：最近一个已结束的入组周。 */
    public static PeriodWindow lastCompletedEnrollWeek(LocalDate joinDate, LocalDate today) {
        if (joinDate == null || today == null) {
            return null;
        }
        long daysSinceJoin = ChronoUnit.DAYS.between(joinDate, today);
        long completed = daysSinceJoin / 7;
        if (completed < 1) {
            return null;
        }
        LocalDate start = joinDate.plusDays((completed - 1) * 7);
        return new PeriodWindow(start, start.plusDays(6));
    }

    /**
     * 校验/对齐手工指定的周起点：须 = join + n*7，且周期已结束。
     */
    public static PeriodWindow enrollWeekFromStart(LocalDate joinDate, LocalDate periodStart, LocalDate today) {
        if (joinDate == null || periodStart == null || today == null) {
            return null;
        }
        if (periodStart.isBefore(joinDate)) {
            return null;
        }
        long offset = ChronoUnit.DAYS.between(joinDate, periodStart);
        if (offset % 7 != 0) {
            return null;
        }
        LocalDate end = periodStart.plusDays(6);
        if (!end.isBefore(today)) {
            return null;
        }
        return new PeriodWindow(periodStart, end);
    }

    public static int enrollWeekIndex(LocalDate joinDate, LocalDate periodStart) {
        if (joinDate == null || periodStart == null) {
            return 0;
        }
        long offset = ChronoUnit.DAYS.between(joinDate, periodStart);
        if (offset < 0) {
            return 0;
        }
        return (int) (offset / 7) + 1;
    }

    /** 昨天是月末 → 上月；否则 null。 */
    public static PeriodWindow calendarMonthJustEnded(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        if (yesterday.getDayOfMonth() != yesterday.lengthOfMonth()) {
            return null;
        }
        return new PeriodWindow(yesterday.withDayOfMonth(1), yesterday);
    }

    public static PeriodWindow lastCompletedCalendarMonth(LocalDate today) {
        LocalDate firstThisMonth = today.withDayOfMonth(1);
        LocalDate end = firstThisMonth.minusDays(1);
        return new PeriodWindow(end.withDayOfMonth(1), end);
    }

    public static PeriodWindow calendarMonthFromStart(LocalDate periodStart, LocalDate today) {
        if (periodStart == null || today == null) {
            return null;
        }
        LocalDate start = periodStart.withDayOfMonth(1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        if (!end.isBefore(today)) {
            return null;
        }
        return new PeriodWindow(start, end);
    }

    /** 昨天是季末 → 上季；否则 null。 */
    public static PeriodWindow calendarQuarterJustEnded(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        int m = yesterday.getMonthValue();
        if (yesterday.getDayOfMonth() != yesterday.lengthOfMonth() || m % 3 != 0) {
            return null;
        }
        LocalDate start = yesterday.minusMonths(2).withDayOfMonth(1);
        return new PeriodWindow(start, yesterday);
    }

    public static PeriodWindow lastCompletedCalendarQuarter(LocalDate today) {
        int m = today.getMonthValue();
        int qStartMonth = ((m - 1) / 3) * 3 + 1;
        LocalDate thisQStart = LocalDate.of(today.getYear(), qStartMonth, 1);
        LocalDate end = thisQStart.minusDays(1);
        LocalDate start = end.minusMonths(2).withDayOfMonth(1);
        return new PeriodWindow(start, end);
    }

    public static PeriodWindow calendarQuarterFromStart(LocalDate periodStart, LocalDate today) {
        if (periodStart == null || today == null) {
            return null;
        }
        int m = periodStart.getMonthValue();
        int qStartMonth = ((m - 1) / 3) * 3 + 1;
        LocalDate start = LocalDate.of(periodStart.getYear(), qStartMonth, 1);
        LocalDate end = start.plusMonths(3).minusDays(1);
        if (!end.isBefore(today)) {
            return null;
        }
        return new PeriodWindow(start, end);
    }

    /** 入组至周期结束的自然日数（含首尾）。 */
    public static long membershipDaysThrough(LocalDate joinDate, LocalDate periodEnd) {
        if (joinDate == null || periodEnd == null || periodEnd.isBefore(joinDate)) {
            return 0;
        }
        return ChronoUnit.DAYS.between(joinDate, periodEnd) + 1;
    }
}
