package com.healix.core.workspace.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 员工个人重点关注患者（按机构隔离）。 */
@Getter
@Setter
public class StaffPatientWatch extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String staffId;
    private String peopleId;
}
