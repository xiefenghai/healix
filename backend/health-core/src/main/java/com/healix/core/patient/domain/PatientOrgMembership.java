package com.healix.core.patient.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者-机构会员关系 */
@Getter
@Setter
public class PatientOrgMembership extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 机构ID */
    private String orgId;
    /** 患者ID（patient_profile.id） */
    private String peopleId;
    /** 状态，见 {@link com.healix.core.patient.enums.MembershipStatusEnum} */
    private String status;
    /** 入组时间 */
    private LocalDateTime joinedAt;
    /** 退出时间 */
    private LocalDateTime leftAt;
    /** 邀请人 staff_profile.id */
    private String invitedByStaffId;
}
