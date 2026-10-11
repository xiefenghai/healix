package com.healix.core.diet.enums;

import com.healix.common.exception.BusinessException;
import java.time.LocalTime;
import java.util.Locale;

public enum MealTypeEnum {
    BREAKFAST("早餐", true),
    LUNCH("午餐", true),
    DINNER("晚餐", true),
    SNACK("加餐", false);

    private final String label;
    /** 正餐：DIET_LOG「记录三餐」的计数口径 */
    private final boolean mainMeal;

    MealTypeEnum(String label, boolean mainMeal) {
        this.label = label;
        this.mainMeal = mainMeal;
    }

    public String label() {
        return label;
    }

    public boolean isMainMeal() {
        return mainMeal;
    }

    public static MealTypeEnum parse(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("请选择餐次");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("无效的餐次");
        }
    }

    /** 按当前时间推断默认餐次。 */
    public static MealTypeEnum byTime(LocalTime time) {
        int h = time.getHour();
        if (h < 10) {
            return BREAKFAST;
        }
        if (h < 15) {
            return LUNCH;
        }
        if (h < 21) {
            return DINNER;
        }
        return SNACK;
    }
}
