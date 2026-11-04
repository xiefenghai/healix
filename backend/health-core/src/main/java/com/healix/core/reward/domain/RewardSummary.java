package com.healix.core.reward.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 患者激励汇总：已终结算的火苗、保护卡、历史最高等级与单患者开关。 */
@Getter
@Setter
public class RewardSummary extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private Integer levelMax;
    private Integer streakDays;
    private Integer bestStreak;
    private Integer freezeCards;
    private LocalDate lastSettledDate;
    private LocalDate lastPushDate;
    private Integer rewardDisabled;
    /** 1 = 不参与也不查看同期百分位 */
    private Integer peerOptOut;
}
