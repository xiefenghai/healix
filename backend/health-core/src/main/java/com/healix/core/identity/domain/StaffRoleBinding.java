package com.healix.core.identity.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 人员-角色绑定 */
@Getter
@Setter
public class StaffRoleBinding extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 人员ID（staff_profile.id） */
    private String staffId;
    /** 角色，见 {@link com.healix.core.identity.enums.StaffRoleEnum}；org_id 一律 NULL */
    private String roleCode;
    /** 角色作用机构；TENANT_ADMIN 可空 */
    private String orgId;
}
