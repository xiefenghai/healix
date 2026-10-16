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
    /** 饮水任务目标毫升；其它类别为空 */
    private Integer targetMl;
    /** 监测类任务关联的指标（BLOOD_GLUCOSE / BLOOD_PRESSURE），录入即自动完成；其它任务为空 */
    private String metricRef;
}
