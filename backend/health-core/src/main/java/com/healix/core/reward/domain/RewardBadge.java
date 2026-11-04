package com.healix.core.reward.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RewardBadge extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String badgeCode;
    private String badgeName;
    private String kind;
    private LocalDate earnedDate;
    private String sourceRef;
}
