package com.healix.core.diet.support;

import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.diet.enums.ActivityLevelEnum;
import com.healix.core.diet.enums.DietGoalTypeEnum;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 热量与营养素目标计算。
 *
 * <ul>
 *   <li>BMR：Mifflin-St Jeor</li>
 *   <li>TDEE = BMR × 活动系数；运动消耗不另行抵扣</li>
 *   <li>减/增重：每 kg 体重约 7700 kcal，按每周速度折算每日差额</li>
 *   <li>安全下限：女 1200 / 男 1500 kcal</li>
 *   <li>三大营养素按病种分配供能比（碳水/蛋白 4 kcal/g，脂肪 9 kcal/g）</li>
 * </ul>
 */
public final class EnergyTargetCalculator {

    public static final int FEMALE_FLOOR_KCAL = 1200;
    public static final int MALE_FLOOR_KCAL = 1500;
    public static final int SODIUM_LIMIT_MG = 2000;
    public static final int FIBER_TARGET_G = 25;
    private static final double KCAL_PER_KG = 7700.0;

    private EnergyTargetCalculator() {}

    public record Input(
            String gender,
            int age,
            double heightCm,
            double weightKg,
            ActivityLevelEnum activity,
            DietGoalTypeEnum goalType,
            double rateKgPerWeek,
            CarePlanTemplateKeyEnum disease) {}

    public record Result(
            int bmrKcal,
            int tdeeKcal,
            int energyKcal,
            boolean floorApplied,
            int carbG,
            int proteinG,
            int fatG,
            int fiberG,
            int sodiumMg,
            Map<String, Double> mealSplit) {}

    /** 供能比：碳水 / 蛋白 / 脂肪 */
    public record MacroRatio(double carb, double protein, double fat) {}

    public static Result calculate(Input in) {
        boolean male = "MALE".equalsIgnoreCase(in.gender());
        double bmr = 10 * in.weightKg() + 6.25 * in.heightCm() - 5 * in.age() + (male ? 5 : -161);
        double tdee = bmr * in.activity().factor();
        double dailyDelta = in.rateKgPerWeek() * KCAL_PER_KG / 7.0;
        double energy = switch (in.goalType()) {
            case LOSE -> tdee - dailyDelta;
            case GAIN -> tdee + dailyDelta;
            case MAINTAIN -> tdee;
        };
        int floor = male ? MALE_FLOOR_KCAL : FEMALE_FLOOR_KCAL;
        boolean floorApplied = false;
        if (energy < floor) {
            energy = floor;
            floorApplied = true;
        }
        int energyKcal = roundTo(energy, 10);
        MacroRatio ratio = macroRatio(in.disease());
        return new Result(
                (int) Math.round(bmr),
                (int) Math.round(tdee),
                energyKcal,
                floorApplied,
                (int) Math.round(energyKcal * ratio.carb() / 4),
                (int) Math.round(energyKcal * ratio.protein() / 4),
                (int) Math.round(energyKcal * ratio.fat() / 9),
                FIBER_TARGET_G,
                SODIUM_LIMIT_MG,
                defaultMealSplit());
    }

    /** 按病种的默认供能比（取推荐区间中值）。 */
    public static MacroRatio macroRatio(CarePlanTemplateKeyEnum disease) {
        if (disease == CarePlanTemplateKeyEnum.DIABETES || disease == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION) {
            return new MacroRatio(0.50, 0.20, 0.30);
        }
        return new MacroRatio(0.55, 0.18, 0.27);
    }

    /** 三餐热量分配：早 30% / 午 40% / 晚 30%（加餐计入当日总量）。 */
    public static Map<String, Double> defaultMealSplit() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("BREAKFAST", 0.3);
        m.put("LUNCH", 0.4);
        m.put("DINNER", 0.3);
        return m;
    }

    private static int roundTo(double v, int step) {
        return (int) (Math.round(v / step) * step);
    }
}
