package com.healix.core.patientcard.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
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
    @JsonIgnore
    private String identityValueHash;
    private String identityValueMask;
    private String mobile;
    /** 列表展示用，取自档案，不落卡片表 */
    private String gender;
    private LocalDate birthday;
}
