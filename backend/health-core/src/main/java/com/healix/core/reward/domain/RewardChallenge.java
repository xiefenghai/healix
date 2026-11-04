package com.healix.core.reward.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 患者当周挑战：周一生成、周日结束、周一结算发豆。 */
@Getter
@Setter
public class RewardChallenge extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String kind;
    private String title;
    private Integer target;
    private Integer progress;
    /** ACTIVE / DONE / FAILED */
    private String status;
    /** AUTO / STAFF */
    private String source;
    private Integer pointsAwarded;
}
