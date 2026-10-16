package com.healix.core.medication.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    /** PRN 同日第几次；定时药恒为 1 */
    private Integer doseSeq;
    private String status;
    private LocalDateTime takenAt;
    /** 1=次日补记 */
    private Integer backfill;
    private String note;
    private String recordedByStaffId;
}
