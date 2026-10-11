package com.healix.core.worktask.catalog;

/** 异常开单指标族。 */
public enum MetricFamily {
    BP,
    GLUCOSE,
    HR,
    BMI;

    public String label() {
        return switch (this) {
            case BP -> "血压";
            case GLUCOSE -> "血糖";
            case HR -> "心率";
            case BMI -> "BMI";
        };
    }

    public boolean matches(String value) {
        return name().equals(value);
    }
}
