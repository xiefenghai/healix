package com.healix.core.medication.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 用药依从性打卡记录。 */
@Getter
@Setter
public class PeopleMedicationIntake extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String medicationId;
    private LocalDate intakeDate;
    private String timeSlot;
    private String status;
    private String note;
    private String recordedByStaffId;
}
