package com.healix.core.assessment.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 录入官方 China-PAR 计算结果（本地不复算系数）。 */
@Getter
@Setter
public class ChinaParRecordRequest {
    /** 10 年发病风险 %，必填，0–100 */
    private BigDecimal tenYearRiskPercent;
    /** 终生风险 %（建议 20–59 且 10 年非高危时录入） */
    private BigDecimal lifetimeRiskPercent;
    /** URBAN / RURAL */
    private String urbanRural;
    /** NORTH / SOUTH（长江为界） */
    private String geoRegion;
    private Boolean onAntihypertensive;
    private Boolean currentSmoker;
    private Boolean diabetes;
    private Boolean familyHistoryAscvd;
    private BigDecimal waistCm;
    private BigDecimal tcMmolL;
    private BigDecimal hdlMmolL;
    private BigDecimal sbp;
    private BigDecimal dbp;
    private Integer ageYears;
    private String gender;
    private String note;
}
