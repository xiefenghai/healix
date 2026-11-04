package com.healix.core.medication.enums;

/** 用药不适症状。 */
public enum MedicationSymptomEnum {
    RASH("皮疹或瘙痒"),
    DIZZY("头晕"),
    GI("胃肠不适"),
    PALPITATION("心慌"),
    FATIGUE("乏力"),
    COUGH("咳嗽"),
    EDEMA("水肿"),
    OTHER("其他");

    private final String label;

    MedicationSymptomEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static String labelOf(String code) {
        try {
            return valueOf(code).label;
        } catch (Exception e) {
            return code;
        }
    }
}
