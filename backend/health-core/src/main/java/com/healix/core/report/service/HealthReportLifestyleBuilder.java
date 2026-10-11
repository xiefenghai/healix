package com.healix.core.report.service;

import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.domain.DietMeal;
import com.healix.core.diet.enums.MealTypeEnum;
import com.healix.core.diet.mapper.DietGoalMapper;
import com.healix.core.diet.mapper.DietMealMapper;
import com.healix.core.diet.support.DietRuleEngine;
import com.healix.core.exercise.domain.ExerciseSession;
import com.healix.core.exercise.enums.ExerciseCategoryEnum;
import com.healix.core.exercise.mapper.ExerciseSessionMapper;
import com.healix.core.exercise.support.ExercisePlanLoader;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 管理报告「饮食 / 运动」章节（content_json.lifestyle）。
 *
 * <p>运动计划任务的完成度已计入 {@code adherence.plan}；这里只统计记录本身。
 */
@Service
@RequiredArgsConstructor
public class HealthReportLifestyleBuilder {

    /** 热量达标：完整三餐日的摄入落在目标 ±10%。 */
    private static final double KCAL_TOLERANCE = 0.10;

    private static final Map<ExerciseCategoryEnum, String> CATEGORY_LABELS = Map.of(
            ExerciseCategoryEnum.AEROBIC, "有氧",
            ExerciseCategoryEnum.RESISTANCE, "力量",
            ExerciseCategoryEnum.FLEXIBILITY, "柔韧",
            ExerciseCategoryEnum.BALANCE, "平衡",
            ExerciseCategoryEnum.DAILY, "日常活动");

    private final DietMealMapper dietMealMapper;
    private final DietGoalMapper dietGoalMapper;
    private final ExerciseSessionMapper exerciseSessionMapper;
    private final ExercisePlanLoader exercisePlanLoader;

    public Map<String, Object> build(String tenantId, String peopleId, LocalDate start, LocalDate end) {
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dayCount", days);
        out.put("diet", buildDiet(tenantId, peopleId, start, end, days));
        out.put("exercise", buildExercise(tenantId, peopleId, start, end, days));
        return out;
    }

    private Map<String, Object> buildDiet(String tenantId, String peopleId, LocalDate start, LocalDate end, int days) {
        DietGoal goal = dietGoalMapper.findCurrent(tenantId, peopleId);
        Integer target = goal != null ? goal.getEnergyKcal() : null;
        Map<LocalDate, List<DietMeal>> byDay = new TreeMap<>();
        for (DietMeal m : dietMealMapper.listByPeopleRange(tenantId, peopleId, start, end)) {
            byDay.computeIfAbsent(m.getMealDate(), k -> new ArrayList<>()).add(m);
        }
        int completeDays = 0;
        int onTargetDays = 0;
        int mealCount = 0;
        int redMeals = 0;
        int yellowMeals = 0;
        double kcalSum = 0;
        double sodiumSum = 0;
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DietMeal>> e : byDay.entrySet()) {
            List<DietMeal> meals = e.getValue();
            boolean complete = mainMealCount(meals) == 3;
            double kcal = meals.stream().mapToDouble(m -> nz(m.getEnergyKcal())).sum();
            mealCount += meals.size();
            redMeals += (int) meals.stream().filter(m -> DietRuleEngine.RED.equals(m.getQualityLevel())).count();
            yellowMeals += (int) meals.stream().filter(m -> DietRuleEngine.YELLOW.equals(m.getQualityLevel())).count();
            if (complete) {
                completeDays++;
                kcalSum += kcal;
                sodiumSum += meals.stream().mapToDouble(m -> nz(m.getSodiumMg())).sum();
                if (target != null && target > 0 && Math.abs(kcal - target) <= target * KCAL_TOLERANCE) {
                    onTargetDays++;
                }
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", e.getKey().toString());
            day.put("meals", meals.size());
            day.put("complete", complete);
            day.put("kcal", Math.round(kcal));
            daily.add(day);
        }
        Map<String, Object> diet = new LinkedHashMap<>();
        diet.put("recordDays", byDay.size());
        diet.put("recordRate", rate(byDay.size(), days));
        diet.put("completeDays", completeDays);
        diet.put("mealCount", mealCount);
        diet.put("targetKcal", target);
        diet.put("avgKcal", completeDays > 0 ? Math.round(kcalSum / completeDays) : null);
        diet.put("onTargetDays", target != null ? onTargetDays : null);
        diet.put("avgSodiumMg", completeDays > 0 ? Math.round(sodiumSum / completeDays) : null);
        diet.put("redMeals", redMeals);
        diet.put("yellowMeals", yellowMeals);
        diet.put("daily", daily);
        return diet;
    }

    private Map<String, Object> buildExercise(
            String tenantId, String peopleId, LocalDate start, LocalDate end, int days) {
        Map<LocalDate, List<ExerciseSession>> byDay = new TreeMap<>();
        for (ExerciseSession s : exerciseSessionMapper.listByPeopleRange(tenantId, peopleId, start, end)) {
            byDay.computeIfAbsent(s.getExerciseDate(), k -> new ArrayList<>()).add(s);
        }
        int totalMin = 0;
        int activeMin = 0;
        int sessionCount = 0;
        int redCount = 0;
        int yellowCount = 0;
        Map<ExerciseCategoryEnum, Integer> minByCategory = new EnumMap<>(ExerciseCategoryEnum.class);
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, List<ExerciseSession>> e : byDay.entrySet()) {
            int dayTotal = 0;
            int dayActive = 0;
            for (ExerciseSession s : e.getValue()) {
                int min = nz(s.getDurationMin());
                dayTotal += min;
                dayActive += nz(s.getActiveMin());
                minByCategory.merge(
                        ExerciseCategoryEnum.parseOr(s.getCategory(), ExerciseCategoryEnum.DAILY), min, Integer::sum);
                if ("RED".equals(s.getLevel())) {
                    redCount++;
                } else if ("YELLOW".equals(s.getLevel())) {
                    yellowCount++;
                }
            }
            totalMin += dayTotal;
            activeMin += dayActive;
            sessionCount += e.getValue().size();
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", e.getKey().toString());
            day.put("totalMin", dayTotal);
            day.put("activeMin", dayActive);
            daily.add(day);
        }
        List<Map<String, Object>> categories = new ArrayList<>();
        minByCategory.entrySet().stream()
                .sorted(Map.Entry.<ExerciseCategoryEnum, Integer>comparingByValue().reversed())
                .forEach(en -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("category", en.getKey().name());
                    c.put("label", CATEGORY_LABELS.get(en.getKey()));
                    c.put("min", en.getValue());
                    categories.add(c);
                });
        int weeklyTarget = exercisePlanLoader.load(tenantId, peopleId, end).weeklyActiveMin();
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("activeDays", byDay.size());
        ex.put("recordRate", rate(byDay.size(), days));
        ex.put("sessionCount", sessionCount);
        ex.put("totalMin", totalMin);
        ex.put("activeMin", activeMin);
        ex.put("avgWeeklyActiveMin", Math.round(activeMin * 7.0 / days));
        ex.put("weeklyActiveMinTarget", weeklyTarget > 0 ? weeklyTarget : null);
        ex.put("redCount", redCount);
        ex.put("yellowCount", yellowCount);
        ex.put("categories", categories);
        ex.put("daily", daily);
        return ex;
    }

    private static int mainMealCount(List<DietMeal> meals) {
        Set<MealTypeEnum> set = EnumSet.noneOf(MealTypeEnum.class);
        for (DietMeal m : meals) {
            try {
                MealTypeEnum t = MealTypeEnum.valueOf(m.getMealType());
                if (t.isMainMeal()) {
                    set.add(t);
                }
            } catch (IllegalArgumentException | NullPointerException ignored) {
                // 历史脏数据不计入
            }
        }
        return set.size();
    }

    private static Double rate(int n, int d) {
        return d > 0 ? Math.round(n * 100.0 / d) / 100.0 : null;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
