package com.healix.core.reward.catalog;

/** 等级：按累计健康豆，只升不降。 */
public enum RewardLevel {
    LV1(1, "萌芽", 0),
    LV2(2, "扎根", 300),
    LV3(3, "繁茂", 1000),
    LV4(4, "硕果", 3000),
    LV5(5, "参天", 8000);

    private final int level;
    private final String title;
    private final int threshold;

    RewardLevel(int level, String title, int threshold) {
        this.level = level;
        this.title = title;
        this.threshold = threshold;
    }

    public int level() {
        return level;
    }

    public String title() {
        return title;
    }

    public int threshold() {
        return threshold;
    }

    public static RewardLevel ofPoints(int lifetime) {
        RewardLevel out = LV1;
        for (RewardLevel l : values()) {
            if (lifetime >= l.threshold) {
                out = l;
            }
        }
        return out;
    }

    public static RewardLevel of(int level) {
        for (RewardLevel l : values()) {
            if (l.level == level) {
                return l;
            }
        }
        return level > LV5.level ? LV5 : LV1;
    }

    /** 下一级；已满级返回 null。 */
    public RewardLevel next() {
        int i = ordinal() + 1;
        return i < values().length ? values()[i] : null;
    }
}
