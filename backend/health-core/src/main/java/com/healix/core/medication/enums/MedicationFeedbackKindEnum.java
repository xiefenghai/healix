package com.healix.core.medication.enums;

/** 患者用药反馈类型。 */
public enum MedicationFeedbackKindEnum {
    ADVERSE("报告不适"),
    ADJUST("申请调整"),
    STOPPED("自述停药"),
    ALLERGY_CONFLICT("过敏冲突");

    private final String label;

    MedicationFeedbackKindEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
