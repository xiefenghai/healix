package com.healix.core.report.catalog;

public enum HealthReportPeriodType {
    WEEK,
    MONTH,
    QUARTER;

    public boolean matches(String value) {
        return name().equals(value);
    }

    public String label() {
        return switch (this) {
            case WEEK -> "周报";
            case MONTH -> "月报";
            case QUARTER -> "三月报";
        };
    }

    public static HealthReportPeriodType require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知报告周期: " + value);
        }
    }
}
