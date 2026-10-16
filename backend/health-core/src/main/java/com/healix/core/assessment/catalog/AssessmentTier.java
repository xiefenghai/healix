package com.healix.core.assessment.catalog;

/** 统一等级：所有引擎映射到同一四档，用于着色、排序与升级判定。 */
public enum AssessmentTier {
    LOW("低", "success", 1),
    MID("中", "warning", 2),
    HIGH("高", "danger", 3),
    VERY_HIGH("很高", "danger", 4);

    private final String label;
    private final String tone;
    private final int rank;

    AssessmentTier(String label, String tone, int rank) {
        this.label = label;
        this.tone = tone;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    public String tone() {
        return tone;
    }

    public int rank() {
        return rank;
    }

    public boolean atLeastHigh() {
        return rank >= HIGH.rank;
    }

    public static AssessmentTier parse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static AssessmentTier max(AssessmentTier a, AssessmentTier b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rank >= b.rank ? a : b;
    }
}
