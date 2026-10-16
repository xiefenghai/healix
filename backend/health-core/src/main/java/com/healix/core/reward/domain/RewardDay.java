package com.healix.core.reward.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RewardDay extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate bizDate;
    private Integer active;
    private Integer fullDay;
    private Integer dueCount;
    private Integer doneCount;
    private Integer freezeUsed;
    private Integer streakAfter;
}
