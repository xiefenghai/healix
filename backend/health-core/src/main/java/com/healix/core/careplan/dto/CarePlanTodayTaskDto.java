package com.healix.core.careplan.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanTodayTaskDto {
    private String id;
    private String taskCode;
    private String title;
    private String category;
    private String frequency;
    private String timeSlot;
    private String checkinStatus;
    private String checkinId;
    private String note;
}
