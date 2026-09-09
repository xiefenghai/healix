package com.healix.core.careplan.enums;

public enum CarePlanTemplateKeyEnum {
    GENERAL,
    DIABETES,
    HYPERTENSION,
    DIABETES_HYPERTENSION;

    public String label() {
        return switch (this) {
            case GENERAL -> "通用健康管理";
            case DIABETES -> "糖尿病管理";
            case HYPERTENSION -> "高血压管理";
            case DIABETES_HYPERTENSION -> "糖尿病+高血压联合管理";
        };
    }
}
