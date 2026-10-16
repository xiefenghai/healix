package com.healix.core.sleep.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SleepLog extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate bizDate;
    /** MAIN 主睡眠 / NAP 午睡 */
    private String segment;
    private Integer durationMin;
    private String quality;
    private LocalTime bedtime;
    private LocalTime wakeTime;
    private String note;
    private String recordedByPeopleId;
    private String recordedByStaffId;
}
