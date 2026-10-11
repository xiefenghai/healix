package com.healix.core.diet.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 患者当前的热量与营养素目标；变更时软删旧行再插新行，保留历史。 */
@Getter
@Setter
public class DietGoal extends BaseEntity {
    private String tenantId;
    private String peopleId;
    /** AUTO / STAFF */
    private String source;
    private String goalType;
    private Double rateKgPerWeek;
    private String activityLevel;
    /** 病种口径，取值同 CarePlanTemplateKeyEnum */
    private String diseaseKey;
    private Double basisWeightKg;
    private Double basisHeightCm;
    private Integer basisAge;
    private String basisGender;
    private Integer bmrKcal;
    private Integer tdeeKcal;
    private Integer energyKcal;
    private Integer carbG;
    private Integer proteinG;
    private Integer fatG;
    private Integer fiberG;
    private Integer sodiumMg;
    private String mealSplitJson;
    private Integer floorApplied;
    private String setByStaffId;
    private String note;
}
