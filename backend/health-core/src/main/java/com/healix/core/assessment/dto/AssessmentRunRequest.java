package com.healix.core.assessment.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssessmentRunRequest {
    /** 指定引擎；空则跑所有 applicable */
    private String engineCode;
}
