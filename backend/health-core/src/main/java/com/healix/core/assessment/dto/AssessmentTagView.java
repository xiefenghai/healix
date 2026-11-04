package com.healix.core.assessment.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 患者列表 / 驾驶舱 / Agent 共用的评估标签。 */
@Data
public class AssessmentTagView {
    private String engineCode;
    private String diseaseCode;
    /** RISK / SEVERITY */
    private String category;
    private String level;
    /** 统一等级 LOW / MID / HIGH / VERY_HIGH */
    private String tier;
    private String text;
    /** danger / warning / success / info */
    private String tone;
    private String title;
    private LocalDateTime assessedAt;
}
