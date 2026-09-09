package com.healix.core.careplan.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanCheckinViewDto {
    private String id;
    private LocalDate checkinDate;
    private String planVersionId;
    private String taskId;
    private String taskTitle;
    private String taskCategory;
    private String timeSlot;
    private String status;
    private String note;
    private String recordedByPeopleId;
    private String recordedByStaffId;
    private LocalDateTime gmtCreated;
}
