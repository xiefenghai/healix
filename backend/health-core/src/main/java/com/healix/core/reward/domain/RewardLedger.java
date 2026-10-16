package com.healix.core.reward.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 健康豆流水；同一 (bizType, bizRef) 的净额即应得，冲正追加负值行。 */
@Getter
@Setter
public class RewardLedger extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate bizDate;
    private String bizType;
    private String bizRef;
    private Integer seq;
    private Integer points;
    private String title;
}
