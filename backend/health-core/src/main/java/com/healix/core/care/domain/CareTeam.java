package com.healix.core.care.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 健管组 */
@Getter
@Setter
public class CareTeam extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String teamCode;
    private String name;
    private String primaryCareManagerStaffId;
    private String primaryDoctorStaffId;
    private String status;
}
