package com.healix.core.medication.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 患者用药清单（患者级，对齐处方笺字段）。 */
@Getter
@Setter
public class PeopleMedication extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String orgId;
    private String prescriptionGroupId;
    private String source;
    private String drugName;
    /** 给药途径：ORAL/INJECTION/INHALATION */
    private String usageMethod;
    /** 频率：QD/BID/TID/... */
    private String frequency;
    private String doseAmount;
    private String doseUnit;
    private LocalDate startDate;
    private LocalDate stopDate;
    /** 用药时机：晨起/饭后/痛时服等 */
    private String timingNote;
    /** 疗程天数；空可表示长期 */
    private Integer courseDays;
    private String timeMorning;
    private String timeNoon;
    private String timeEvening;
    private String timeBedtime;
    private Integer hasAdverseReaction;
    private String remark;
    private String status;
    private String createdByStaffId;
    private String updatedByStaffId;
}
