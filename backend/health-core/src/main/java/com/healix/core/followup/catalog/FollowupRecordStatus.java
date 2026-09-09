package com.healix.core.followup.catalog;

public enum FollowupRecordStatus {
    OPEN,
    DONE,
    CANCELLED;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
