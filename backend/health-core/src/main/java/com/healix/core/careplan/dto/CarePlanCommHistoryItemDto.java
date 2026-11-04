package com.healix.core.careplan.dto;

import lombok.Getter;
import lombok.Setter;

/** 方案沟通多轮历史（来自 PLAN_COMMUNICATION 随访）。 */
@Getter
@Setter
public class CarePlanCommHistoryItemDto {
    private int round;
    private String followupId;
    private String result;
    private String resultLabel;
    private String customerFeedback;
    private String changePoints;
    private boolean faceToFace;
    private String followupMethod;
    private String assigneeStaffId;
    private String assigneeName;
    private String completedAt;
    private String draftId;
}
