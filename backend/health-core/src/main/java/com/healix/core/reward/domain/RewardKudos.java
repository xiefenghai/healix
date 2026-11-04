package com.healix.core.reward.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RewardKudos extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String staffId;
    private LocalDate kudosDate;
    private String message;
}
