package com.healix.core.metadata.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PeopleMetadataInfo extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String metadataCode;
    private String metadataValue;
    private String sourceBizCode;
    private String sourceClientCode;
}
