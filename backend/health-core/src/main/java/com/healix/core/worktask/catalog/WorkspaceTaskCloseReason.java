package com.healix.core.worktask.catalog;

public enum WorkspaceTaskCloseReason {
    CONDITION,
    FORM,
    EXPIRED,
    CANCEL;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
