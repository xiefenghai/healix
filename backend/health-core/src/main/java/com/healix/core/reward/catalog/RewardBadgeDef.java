package com.healix.core.reward.catalog;

import java.util.Optional;

/** 行为徽章（一次性，+30 豆）。结果荣誉徽章编码以 {@code OUTCOME_} 开头，不在此枚举。 */
public enum RewardBadgeDef {
    PIONEER("开拓者", "激励体系上线时已在坚持管理", "🚀"),
    FIRST_FULL_DAY("首个全勤日", "某天的方案任务全部完成", "🌟"),
    FIRST_FULL_WEEK("全勤一周", "连续 7 天方案任务全部完成", "🏅"),
    STREAK_7("火苗 7 天", "连续打卡 7 天", "🔥"),
    STREAK_21("火苗 21 天", "连续打卡 21 天", "🔥"),
    STREAK_60("火苗 60 天", "连续打卡 60 天", "🔥"),
    STREAK_100("火苗 100 天", "连续打卡 100 天", "🔥"),
    VITAL_50("测量达人", "累计自测 50 次", "📈"),
    PHOTO_30("饮食记录家", "拍照记录 30 餐", "📷");

    public static final String KIND_BEHAVIOR = "BEHAVIOR";
    public static final String KIND_OUTCOME = "OUTCOME";
    public static final String OUTCOME_PREFIX = "OUTCOME_";
    public static final int POINTS = 30;

    private final String title;
    private final String condition;
    private final String icon;

    RewardBadgeDef(String title, String condition, String icon) {
        this.title = title;
        this.condition = condition;
        this.icon = icon;
    }

    public String title() {
        return title;
    }

    public String condition() {
        return condition;
    }

    public String icon() {
        return icon;
    }

    public static Optional<RewardBadgeDef> find(String code) {
        for (RewardBadgeDef d : values()) {
            if (d.name().equals(code)) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }
}
