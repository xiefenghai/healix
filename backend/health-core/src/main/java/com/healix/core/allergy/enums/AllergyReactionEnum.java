package com.healix.core.allergy.enums;

import java.util.Locale;

/** 反应表现；highRisk 的表现一旦出现，条目即标记为高危。 */
public enum AllergyReactionEnum {
    RASH("皮疹/荨麻疹", false),
    ITCH("瘙痒", false),
    ANGIOEDEMA("口唇/眼睑肿胀", true),
    DYSPNEA("喘息/呼吸困难", true),
    ANAPHYLAXIS("过敏性休克", true),
    NAUSEA_VOMIT("恶心呕吐", false),
    ABDOMINAL("腹痛腹泻", false),
    RHINITIS("鼻塞流涕/喷嚏", false),
    EYE("眼痒流泪", false),
    OTHER("其他", false);

    private final String label;
    private final boolean highRisk;

    AllergyReactionEnum(String label, boolean highRisk) {
        this.label = label;
        this.highRisk = highRisk;
    }

    public String label() {
        return label;
    }

    public boolean highRisk() {
        return highRisk;
    }

    public static AllergyReactionEnum parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
