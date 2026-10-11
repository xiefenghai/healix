package com.healix.core.careplan.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 管理方案执行任务打卡记录。 */
@Getter
@Setter
public class CarePlanTaskCheckin extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String planId;
    private String planVersionId;
    private String taskId;
    private LocalDate checkinDate;
    private String timeSlot;
    private String status;
    private String note;
    private String recordedByPeopleId;
    private String recordedByStaffId;
}
