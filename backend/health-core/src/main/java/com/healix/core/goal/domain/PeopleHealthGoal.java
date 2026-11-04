package com.healix.core.goal.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PeopleHealthGoal extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String enrollmentId;
    private String primaryAppeal;
    private Integer doctorAdvised;
    private String appealText;
    private String goalMode;
    private String goalsJson;
    private String preferencesJson;
    private String source;
    private String sourceRefId;
}
