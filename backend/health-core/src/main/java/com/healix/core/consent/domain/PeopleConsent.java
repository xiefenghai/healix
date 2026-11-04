package com.healix.core.consent.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PeopleConsent extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String consentCode;
    private Integer given;
    private String method;
    private String sourceRefId;
    private String recordedByType;
    private String recordedById;
    private LocalDateTime recordedAt;
}
