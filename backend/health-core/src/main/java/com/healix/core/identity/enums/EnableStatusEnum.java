package com.healix.core.identity.enums;

/**
 * 通用启用状态：机构、Staff/Ops/Patient 账号等。
 */
public enum EnableStatusEnum {
    /** 正常 */
    ACTIVE,
    /** 已停用 */
    DISABLED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
