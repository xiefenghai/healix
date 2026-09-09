package com.healix.core.patientcard.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** C 端账号下的就诊人卡片 */
@Getter
@Setter
public class AccountPatient extends BaseEntity {
    private String tenantId;
    private String accountId;
    private String peopleId;
    private String displayName;
    private String relation;
    private String identityType;
    private String identityValueHash;
    private String identityValueMask;
    private String mobile;
}
