package com.healix.core.careplan.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanTask extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String planId;
    private String planVersionId;
    private String taskCode;
    private String title;
    private String category;
    private String frequency;
    private String timeSlot;
    private String relatedRef;
    private Integer enabled;
    private Integer sortOrder;
    private String payloadJson;
}
