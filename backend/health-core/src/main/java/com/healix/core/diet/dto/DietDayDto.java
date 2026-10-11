package com.healix.core.diet.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 饮食日记某一天：目标、摄入、剩余、三餐与提示。 */
@Getter
@Setter
public class DietDayDto {
    private LocalDate date;
    private DietGoalDto goal;
    private NutrientsDto intake;
    /** 目标 − 摄入；无目标时为 null（运动消耗不抵扣） */
    private Integer remainingKcal;
    /** 各正餐热量目标 */
    private Map<String, Integer> mealTargetsKcal;
    /** 已记录的正餐 */
    private List<String> mainMealsLogged;
    private boolean threeMealsComplete;
    private List<DietFlagDto> dayFlags;
    /** 管理方案饮食原则 */
    private List<String> principles;
    private List<DietMealDto> meals;
}
