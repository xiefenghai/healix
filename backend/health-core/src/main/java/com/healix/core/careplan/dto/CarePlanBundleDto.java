package com.healix.core.careplan.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanBundleDto {
    private CarePlanHeadDto plan;
    private CarePlanDraftDto draft;
    private CarePlanVersionDto activeVersion;
}
