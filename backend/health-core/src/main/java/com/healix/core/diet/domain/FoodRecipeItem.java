package com.healix.core.diet.domain;

import lombok.Getter;
import lombok.Setter;

/** 家常菜菜谱组成：一份菜的生料克数。 */
@Getter
@Setter
public class FoodRecipeItem {
    private Long pkId;
    private String dishCode;
    private String ingredientCode;
    private Double grams;
    private Integer oilAdjustable;
    private Integer sortOrder;
}
