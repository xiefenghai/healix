package com.healix.core.diet.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DietMealDto {
    private String id;
    private LocalDate mealDate;
    private String mealType;
    private LocalDateTime eatenAt;
    private String source;
    private NutrientsDto nutrients;
    private Double estimatedKcalRatio;
    private String qualityLevel;
    private List<DietFlagDto> flags;
    private String note;
    private String staffComment;
    private LocalDateTime commentedAt;
    private boolean recordedByStaff;
    private List<DietMealItemDto> items;
}
