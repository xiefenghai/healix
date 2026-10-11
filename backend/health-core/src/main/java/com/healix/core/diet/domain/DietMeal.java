package com.healix.core.diet.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 饮食记录餐次；营养合计为保存时快照。 */
@Getter
@Setter
public class DietMeal extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate mealDate;
    private String mealType;
    private LocalDateTime eatenAt;
    private String source;
    private Double energyKcal;
    private Double carbG;
    private Double proteinG;
    private Double fatG;
    private Double fiberG;
    private Double sodiumMg;
    private Double estimatedKcalRatio;
    private String qualityLevel;
    private String flagsJson;
    private String note;
    private String staffComment;
    private String commentStaffId;
    private LocalDateTime commentedAt;
    private String recordedByPeopleId;
    private String recordedByStaffId;
}
