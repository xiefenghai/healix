package com.healix.core.diet.dto;

import java.time.LocalDate;

/** 区间按日汇总（日历 / 趋势）。 */
public record DietDaySummaryDto(
        LocalDate date,
        NutrientsDto intake,
        Integer targetKcal,
        int mealCount,
        boolean threeMealsComplete,
        int redMeals,
        int yellowMeals) {}
