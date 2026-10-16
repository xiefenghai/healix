package com.healix.core.reward.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 激励接口出参。 */
public final class RewardViews {

    private RewardViews() {}

    /**
     * @param streak 展示火苗（已终结算 + 今天/昨天推演）
     * @param todayActive 今天是否已算打卡日
     * @param yesterdayPending 昨天方案还差几项（可补，>0 时首页提示）
     */
    public record Summary(
            boolean enabled,
            int points,
            int weekPoints,
            int todayPoints,
            Level level,
            int streak,
            int bestStreak,
            int freezeCards,
            boolean todayActive,
            int todayActions,
            int planDue,
            int planDone,
            int yesterdayPending,
            boolean yesterdayActive,
            List<Badge> recentBadges,
            Challenge challenge,
            Peer peer) {

        public static Summary disabled() {
            return new Summary(
                    false, 0, 0, 0, null, 0, 0, 0, false, 0, 0, 0, 0, false, List.of(), null, null);
        }
    }

    public record Challenge(
            String id,
            String kind,
            String title,
            int target,
            int progress,
            String status,
            LocalDate periodStart,
            LocalDate periodEnd,
            double progressRatio,
            int pointsAwarded) {}

    /**
     * @param available 池子 ≥ 10 且未退出时为 true
     * @param percentile 超过同期百分之几（0–100）；unavailable 时为 null
     * @param poolSize 参与计算的同期人数（含自己）
     * @param optOut 用户是否已关闭对比
     */
    public record Peer(boolean available, Integer percentile, int poolSize, boolean optOut, String metricLabel) {}

    /** @param progress 0–1，距下一级；满级为 1 */
    public record Level(int level, String title, String nextTitle, Integer nextThreshold, int lifetime, double progress) {}

    /**
     * @param earned 是否已获得（未获得置灰并展示 condition）
     */
    public record Badge(
            String code, String name, String icon, String kind, String condition, boolean earned, LocalDate earnedDate) {}

    public record LedgerItem(String bizType, String title, int points, LocalDateTime createdAt) {}

    public record LedgerDay(LocalDate date, int total, List<LedgerItem> items) {}

    public record Kudos(String staffId, String staffName, String message, LocalDateTime createdAt) {}

    public record StaffView(
            boolean tenantEnabled,
            boolean patientEnabled,
            Summary summary,
            List<Badge> badges,
            List<LedgerDay> ledger,
            List<Kudos> kudos,
            boolean kudosSentToday,
            int backfills7d,
            Challenge challenge) {}

    public record KudosResult(boolean sent, String message) {}

    /** 驾驶舱本周激励摘要 */
    public record CockpitWeek(int weekPoints, int fullDayCount, int activeTodayCount) {}
}
