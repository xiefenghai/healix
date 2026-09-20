package com.healix.core.cockpit.dto;

import lombok.Data;

/** 顶栏 / 驾驶舱摘要：待办、超期任务数；左栏 Tab 人数（仅任务口径）。 */
@Data
public class CockpitSummaryDto {
    private int openTaskCount;
    /** @deprecated 驾驶舱暂不使用红人；保留字段兼容旧客户端，恒为 0 */
    private int redCount;
    private int overdueCount;
    /** 左栏「立即处理」人数（当前 = 有我名下 OPEN 任务的人） */
    private int urgentCount;
    /** 左栏「今日关注」人数（非高紧迫在办） */
    private int watchCount;
    /** 左栏「我的在办」人数（有我名下 OPEN 任务的人） */
    private int mineCount;
}
