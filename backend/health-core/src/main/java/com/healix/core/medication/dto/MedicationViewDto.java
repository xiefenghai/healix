package com.healix.core.medication.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MedicationViewDto {
    private String id;
    private String peopleId;
    private String prescriptionGroupId;
    private String source;
    private String drugName;
    private String usageMethod;
    private String frequency;
    private String doseAmount;
    private String doseUnit;
    private LocalDate startDate;
    private LocalDate stopDate;
    private String timingNote;
    private Integer courseDays;
    private Boolean hasAdverseReaction;
    private String remark;
    private String status;
    /** 每日应服次数（由 frequency 解析，PRN 为 0） */
    private int dueDoseCount;
    /** 按需服药：不判漏服 */
    private boolean prn;
    /** 建议打卡时段，C 端按此渲染打卡格子 */
    private List<String> suggestedSlots;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
