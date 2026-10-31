package com.healix.core.worktask.catalog;

public enum WorkspaceTaskCloseReason {
    CONDITION,
    FORM,
    EXPIRED,
    CANCEL,
    /** 健管师已核对/跟进办结（自报更新、评估升级、疑似暂不建档、问卷过期等） */
    REVIEWED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
