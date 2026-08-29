package com.healix.core.revision.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FieldRevisionBatch extends BaseEntity {
    private String tenantId;
    private String targetPeopleId;
    private String operatorType;
    private String operatorId;
    private String bizType;
    private String bizKey;
    private Integer versionBefore;
    private Integer versionAfter;
    private String orgId;
}
