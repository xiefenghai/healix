package com.healix.core.worktask.catalog;

public enum WorkspaceTaskStatus {
    OPEN,
    DONE,
    CANCELLED,
    EXPIRED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
