package com.healix.core.patient.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 患者主管机构/主管健管师 */
@Getter
@Setter
public class PatientCareAssignment extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 患者ID */
    private String peopleId;
    /** 主管机构ID */
    private String primaryOrgId;
    /** 主管健管师 staff_profile.id */
    private String primaryCareManagerStaffId;
}
