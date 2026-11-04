package com.healix.core.assessment.domain;

import com.healix.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 评估快照（跟人，无机构字段；只在结果变化时新增）。 */
@Getter
@Setter
public class PeopleAssessmentSnapshot extends BaseEntity {
    private String tenantId;
    private String peopleId;
    /** RISK / SEVERITY */
    private String kind;
    private String engineCode;
    private String diseaseCode;
    private String rulePackVersion;
    private String status;
    private String level;
    private String tier;
    private BigDecimal score;
    private BigDecimal probability;
    private String resultJson;
    private String inputSnapshotJson;
    private String fingerprint;
    private String triggerSource;
    private LocalDateTime assessedAt;
    private String assessedByStaffId;
}
