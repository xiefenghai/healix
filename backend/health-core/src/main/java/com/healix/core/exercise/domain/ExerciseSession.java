package com.healix.core.exercise.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 运动记录；MET、活跃分钟、热量为保存时快照。 */
@Getter
@Setter
public class ExerciseSession extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate exerciseDate;
    private LocalDateTime startedAt;
    private Integer durationMin;
    private String typeCode;
    private String typeName;
    private String category;
    private String intensity;
    private Integer avgHr;
    private Integer distanceM;
    private Double metValue;
    private Integer activeMin;
    private Double energyKcal;
    private Integer weightEstimated;
    private String symptomsJson;
    private String symptomLevel;
    private String flagsJson;
    private String level;
    private String alertTaskId;
    private String carePlanTaskId;
    private String source;
    private String deviceVendor;
    private String externalId;
    private String note;
    private String recordedByPeopleId;
    private String recordedByStaffId;
}
