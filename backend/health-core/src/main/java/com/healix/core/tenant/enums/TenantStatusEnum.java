package com.healix.core.tenant.enums;

/** 租户状态 */
public enum TenantStatusEnum {
    /** 正常 */
    ACTIVE,
    /** 已暂停 */
    SUSPENDED,
    /** 已关闭 */
    CLOSED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
