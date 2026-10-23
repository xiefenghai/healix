package com.healix.core.worktask.catalog;

public enum WorkspaceTaskCloseReason {
    CONDITION,
    FORM,
    EXPIRED,
    CANCEL,
    /** 患者自报更新：健管师已核对，无需再跟 */
    REVIEWED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
