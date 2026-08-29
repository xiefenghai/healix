package com.healix.core.patient.enums;

/** 患者基础档案来源 */
public enum PatientArchiveSourceEnum {
    /** B 端建档 */
    B_SIDE,
    /** C 端入组（预留） */
    C_JOIN;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
