package com.healix.core.exercise.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExerciseSessionDto {
    private String id;
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
    private boolean weightEstimated;
    private List<String> symptoms;
    private String symptomLevel;
    private List<ExerciseFlagDto> flags;
    private String level;
    private String alertTaskId;
    private String carePlanTaskId;
    private String source;
    private String note;
    private boolean recordedByStaff;
    /** 当前视角是否可编辑：患者只能改自录，健管师只能改代录 */
    private boolean editable;
}
