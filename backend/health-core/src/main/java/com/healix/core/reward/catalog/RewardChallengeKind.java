package com.healix.core.reward.catalog;

/** 周挑战种类（自动推荐用）。 */
public enum RewardChallengeKind {
    FULL_DAYS("本周全勤", "天", "本周完成全部方案任务的天数"),
    VITAL_COUNT("本周测量", "次", "本周本人测量次数（血压/血糖等，同次合并）"),
    PHOTO_MEAL("本周拍照记餐", "餐", "本周拍照识别饮食的餐次数");

    public static final int POINTS = 50;

    private final String title;
    private final String unit;
    private final String condition;

    RewardChallengeKind(String title, String unit, String condition) {
        this.title = title;
        this.unit = unit;
        this.condition = condition;
    }

    public String title() {
        return title;
    }

    public String unit() {
        return unit;
    }

    public String condition() {
        return condition;
    }

    public String displayTitle(int target) {
        return title + " ≥ " + target + unit;
    }
}
