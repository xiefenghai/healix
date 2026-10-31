package com.healix.core.careplan.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlan extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String status;
    private String title;
    private String goalSummary;
    private String diseaseTagsJson;
    private String currentVersionId;
    private Integer version;
    private String createdByStaffId;
    private String updatedByStaffId;
    /** 最近一次审核通过时间 */
    private java.time.LocalDateTime reviewedAt;
    private String reviewedByStaffId;
    /** ACCEPTED / NEED_MODIFY / NEED_REGENERATE */
    private String lastCommResult;
    private java.time.LocalDateTime lastCommAt;
    private String lastCommFollowupId;
    private String lastCommNote;
}
