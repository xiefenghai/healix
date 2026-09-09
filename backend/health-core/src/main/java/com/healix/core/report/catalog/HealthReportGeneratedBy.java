package com.healix.core.report.catalog;

public enum HealthReportGeneratedBy {
    JOB,
    MANUAL;

    public boolean matches(String value) {
        return name().equals(value);
    }
}
