package com.healix.core.identity.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 人员-机构绑定 */
@Getter
@Setter
public class StaffOrgBinding extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 人员ID（staff_profile.id） */
    private String staffId;
    /** 机构ID */
    private String orgId;
    /** 是否默认工作机构：1是 0否 */
    private Integer isDefault;
}
