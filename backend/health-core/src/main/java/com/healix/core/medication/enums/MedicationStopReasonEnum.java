package com.healix.core.medication.enums;

/** 患者自述停药原因。 */
public enum MedicationStopReasonEnum {
    IMPROVED("自觉好转"),
    SIDE_EFFECT("副作用"),
    DOCTOR("医生让停"),
    OTHER("其他");

    private final String label;

    MedicationStopReasonEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
