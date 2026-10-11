package com.healix.core.diet.dto;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DietGoalDto {
    private String id;
    /** AUTO / STAFF */
    private String source;
    private String goalType;
    private Double rateKgPerWeek;
    private String activityLevel;
    private String diseaseKey;
    private String diseaseLabel;
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
    /** BREAKFAST/LUNCH/DINNER → 热量占比 */
    private Map<String, Double> mealSplit;
    private boolean floorApplied;
    private String note;
    private LocalDateTime updatedAt;
}
