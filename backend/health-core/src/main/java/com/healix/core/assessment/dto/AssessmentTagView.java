package com.healix.core.assessment.dto;

import lombok.Data;

/** 患者列表 / 驾驶舱共用的评估标签展示。 */
@Data
public class AssessmentTagView {
    private String engineCode;
    private String text;
    /** danger / warning / success / info / muted */
    private String tone;
    private String title;
}
