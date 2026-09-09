package com.healix.core.medication.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MedicationIntakeViewDto {
    private String id;
    private String medicationId;
    private String drugName;
    private LocalDate intakeDate;
    private String timeSlot;
    private String status;
    private String note;
    private LocalDateTime gmtCreated;
}
