package com.healix.core.identity.enums;

/** B 端功能角色（staff_role_binding.role_code，org_id 一律 NULL） */
public enum StaffRoleEnum {
    /** 租户管理员 */
    TENANT_ADMIN,
    /** 健管师 */
    CARE_MANAGER,
    /** 医生 */
    DOCTOR,
    /** 运营 */
    TENANT_OPERATOR,
    /** 机构管理员（已废弃） */
    @Deprecated
    ORG_ADMIN;

    /** 租户管理员可分配/替换的岗位角色（不含 TENANT_ADMIN） */
    public boolean isAssignableByTenantAdmin() {
        return this == CARE_MANAGER || this == DOCTOR || this == TENANT_OPERATOR;
    }

    public static StaffRoleEnum requireAssignable(String code) {
        StaffRoleEnum role = valueOf(code);
        if (!role.isAssignableByTenantAdmin()) {
            throw new IllegalArgumentException("不可分配该角色：" + code);
        }
        return role;
    }

    public static boolean isAssignableCode(String code) {
        try {
            return valueOf(code).isAssignableByTenantAdmin();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean matches(String value) {
        return name().equals(value);
    }
}
