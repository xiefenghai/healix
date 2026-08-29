package com.healix.core.portal.enums;

/** 端入口 / JWT aud */
public enum PortalEnum {
    /** C 端（患者） */
    C("c"),
    /** B 端（租户员工） */
    B("b"),
    /** Ops 端（平台） */
    OPS("ops");

    private final String code;

    PortalEnum(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static PortalEnum fromCode(String code) {
        for (PortalEnum portal : values()) {
            if (portal.code.equals(code)) {
                return portal;
            }
        }
        throw new IllegalArgumentException("Unknown portal: " + code);
    }

    public boolean matchesCode(String value) {
        return code.equals(value);
    }
}
