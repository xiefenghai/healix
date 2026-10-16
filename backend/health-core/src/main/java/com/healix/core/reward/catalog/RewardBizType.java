package com.healix.core.reward.catalog;

import java.util.List;

/** 健康豆流水类型。SYNCED 由源表同步，FULL_DAY/BADGE 由终结算写入。 */
public enum RewardBizType {
    PLAN("完成方案任务"),
    MED("服药打卡"),
    VITAL("测量"),
    DIET("记录饮食"),
    EXERCISE("记录运动"),
    REPORT_READ("阅读管理报告"),
    FULL_DAY("全勤日"),
    BADGE("获得徽章"),
    CHALLENGE("完成周挑战");

    /** 读时同步、可被冲正的类型。 */
    public static final List<RewardBizType> SYNCED = List.of(PLAN, MED, VITAL, DIET, EXERCISE, REPORT_READ);

    /** 计入"打卡日"的类型（G14）。 */
    public static final List<RewardBizType> ACTIVE = List.of(PLAN, MED, VITAL);

    private final String label;

    RewardBizType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static List<String> names(List<RewardBizType> types) {
        return types.stream().map(Enum::name).toList();
    }
}
