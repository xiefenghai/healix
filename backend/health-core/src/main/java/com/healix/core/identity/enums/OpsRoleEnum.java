package com.healix.core.identity.enums;

/** Ops 平台角色（ops_account.role_code） */
public enum OpsRoleEnum {
    /** 超级管理员 */
    SUPER_ADMIN,
    /** 运营人员（只读本域） */
    OPERATOR,
    /** 审计员 */
    AUDITOR;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
