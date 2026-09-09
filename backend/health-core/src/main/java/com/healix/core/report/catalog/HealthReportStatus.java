package com.healix.core.report.catalog;

public enum HealthReportStatus {
    DRAFT,
    PUBLISHED,
    SKIPPED;

    public boolean matches(String value) {
        return name().equals(value);
    }

    public String label() {
        return switch (this) {
            case DRAFT -> "待审阅";
            case PUBLISHED -> "已发布";
            case SKIPPED -> "已跳过";
        };
    }

    public static HealthReportStatus require(String value) {
        try {
            return valueOf(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("未知报告状态: " + value);
        }
    }
}
