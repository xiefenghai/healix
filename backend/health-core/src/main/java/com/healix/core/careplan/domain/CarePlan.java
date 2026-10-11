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
}
