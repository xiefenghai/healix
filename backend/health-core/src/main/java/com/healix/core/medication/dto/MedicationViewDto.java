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
    private String drugCode;
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
    /** 每日应服次数（= 定时时段数，PRN 为 0） */
    private int dueDoseCount;
    /** 按需服药：不判漏服 */
    private boolean prn;
    /** 服药时段（slots_json 优先，否则按频次 + 时机推导） */
    private List<String> suggestedSlots;
    /** 时段是否为人工指定 */
    private boolean slotsCustom;
    /** 患者能否修改药名 / 剂量 / 频次等医疗字段（仅自添加药） */
    private boolean patientEditable;
    /** 自添加药是否已被医护核对；非自添加药为 null */
    private Boolean verified;
    private LocalDateTime verifiedAt;
    private String allergyConflict;
    private String stopReason;
    /** 未处理的患者反馈数 */
    private int openFeedbackCount;
    private LocalDateTime gmtCreated;
    private LocalDateTime gmtModified;
}
