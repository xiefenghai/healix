package com.healix.core.careplan.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanBundleDto {
    private CarePlanHeadDto plan;
    private CarePlanDraftDto draft;
    private CarePlanVersionDto activeVersion;
    /** 方案沟通多轮历史（按时间升序，round 从 1 起）。 */
    private List<CarePlanCommHistoryItemDto> communicationHistory = new ArrayList<>();
}
