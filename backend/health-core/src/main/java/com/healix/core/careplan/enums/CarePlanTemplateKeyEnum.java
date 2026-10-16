package com.healix.core.careplan.enums;

/**
 * 管理方案类型。新生成仅写五类；旧键 {@link #GENERAL}/{@link #DIABETES_HYPERTENSION} 仅兼容读入。
 */
public enum CarePlanTemplateKeyEnum {
    COMPREHENSIVE,
    DIABETES,
    HYPERTENSION,
    OBESITY,
    WELLNESS,
    /** @deprecated 映射为 {@link #WELLNESS} */
    @Deprecated
    GENERAL,
    /** @deprecated 映射为 {@link #COMPREHENSIVE} */
    @Deprecated
    DIABETES_HYPERTENSION;

    /** 列表/标题用短名（不含「方案」后缀） */
    public String label() {
        return switch (canonical()) {
            case COMPREHENSIVE -> "综合管理";
            case DIABETES -> "糖尿病管理";
            case HYPERTENSION -> "高血压管理";
            case OBESITY -> "肥胖管理";
            case WELLNESS -> "健康管理";
            default -> "健康管理";
        };
    }

    /** 默认标题前缀，如「糖尿病管理方案」 */
    public String titlePrefix() {
        return label() + "方案";
    }

    /** 旧键归一到五类 */
    public CarePlanTemplateKeyEnum canonical() {
        return switch (this) {
            case GENERAL -> WELLNESS;
            case DIABETES_HYPERTENSION -> COMPREHENSIVE;
            default -> this;
        };
    }

    public boolean isSpecialty() {
        CarePlanTemplateKeyEnum c = canonical();
        return c == DIABETES || c == HYPERTENSION || c == OBESITY;
    }

    public static CarePlanTemplateKeyEnum parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)).canonical();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
