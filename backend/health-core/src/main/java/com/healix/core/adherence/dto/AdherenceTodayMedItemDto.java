package com.healix.core.adherence.dto;

import java.util.List;
import lombok.Data;

/** 当日在用药打卡明细。 */
@Data
public class AdherenceTodayMedItemDto {
    private String medicationId;
    private String drugName;
    private String frequency;
    private String doseAmount;
    private String doseUnit;
    /** 当日应服次数（按频次展开），PRN 为 0 */
    private int dueDoseCount;
    /** 当日已服次数 */
    private int takenDoseCount;
    /** 按需服药：不判漏服 */
    private boolean prn;
    /** 已打卡时段码 */
    private List<String> takenSlots;
    /** TAKEN 全部完成 / PARTIAL 部分完成 / PENDING 未打卡 */
    private String status;
}
