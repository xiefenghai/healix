package com.healix.core.diet.dto;

import java.util.List;

/** 食物库条目（营养素为每 100g 可食部）。 */
public record FoodItemDto(
        String code,
        String name,
        List<String> aliases,
        String kind,
        String category,
        double energyKcal,
        Double proteinG,
        Double fatG,
        Double carbG,
        Double fiberG,
        Double sodiumMg,
        Integer gi,
        String giLevel,
        List<FoodUnitDto> units,
        List<String> tags,
        Double servingGrams,
        String precisionLevel,
        String dataSource,
        boolean needsReview,
        List<RecipeLineDto> recipe) {

    public record FoodUnitDto(String unit, double grams) {}

    public record RecipeLineDto(String code, String name, double grams, boolean oilAdjustable) {}
}
