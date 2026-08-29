package com.healix.common.constant;

/**
 * @deprecated 请直接使用 {@code com.healix.core.identity.enums.StaffRoleEnum} /
 *     {@code com.healix.core.identity.enums.OpsRoleEnum}。
 *     本类仅作过渡兼容，常量值等于对应枚举 {@code name()}。
 */
@Deprecated
public final class RoleCodes {

    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    @Deprecated
    public static final String ORG_ADMIN = "ORG_ADMIN";
    public static final String CARE_MANAGER = "CARE_MANAGER";
    public static final String DOCTOR = "DOCTOR";
    public static final String TENANT_OPERATOR = "TENANT_OPERATOR";

    public static final String OPS_SUPER_ADMIN = "SUPER_ADMIN";
    public static final String OPS_OPERATOR = "OPERATOR";
    public static final String OPS_AUDITOR = "AUDITOR";

    private RoleCodes() {
    }

    public static boolean isAssignableByTenantAdmin(String roleCode) {
        return CARE_MANAGER.equals(roleCode) || DOCTOR.equals(roleCode) || TENANT_OPERATOR.equals(roleCode);
    }
}
