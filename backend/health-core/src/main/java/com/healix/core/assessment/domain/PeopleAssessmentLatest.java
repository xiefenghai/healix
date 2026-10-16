package com.healix.core.assessment.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 每人每引擎最新结果（列表 / 标签 / 闭环只读这张表）。 */
@Getter
@Setter
public class PeopleAssessmentLatest {
    private String tenantId;
    private String peopleId;
    private String engineCode;
    private String category;
    private String diseaseCode;
    private String snapshotId;
    private String status;
    private String level;
    private String levelLabel;
    private String tier;
    private BigDecimal score;
    private Integer active;
    private String fingerprint;
    private LocalDateTime assessedAt;
    private LocalDateTime evaluatedAt;

    public boolean effective() {
        return Integer.valueOf(1).equals(active);
    }
}
