package com.healix.core.diet.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DietMealItemDto {
    private String id;
    private String foodCode;
    private String foodName;
    private Double amount;
    private String unit;
    private Double grams;
    private String oilLevel;
    private Double energyKcal;
    private Double carbG;
    private Double proteinG;
    private Double fatG;
    private Double fiberG;
    private Double sodiumMg;
    private String precisionLevel;
    private Double confidence;
    private Boolean gramsConfirmed;
}
