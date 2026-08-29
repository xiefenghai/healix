package com.healix.core.people.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 患者身份证件 */
@Getter
@Setter
public class PeopleIdentity extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String identityType;
    private String identityValueHash;
    private String identityValueCipher;
    private String identityValueMask;
}
