package com.healix.core.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.domain.DietMeal;
import com.healix.core.diet.domain.DietMealItem;
import com.healix.core.diet.enums.MealTypeEnum;
import com.healix.core.diet.mapper.DietGoalMapper;
import com.healix.core.diet.mapper.DietMealItemMapper;
import com.healix.core.diet.mapper.DietMealMapper;
import com.healix.core.diet.support.DietRuleEngine;
import com.healix.core.diet.support.EnergyTargetCalculator;
import com.healix.core.exercise.domain.ExerciseSession;
import com.healix.core.exercise.enums.ExerciseCategoryEnum;
import com.healix.core.exercise.mapper.ExerciseSessionMapper;
import com.healix.core.exercise.support.ExercisePlanLoader;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    private static final int MAX_FLAG_ROWS = 5;
    private static final int MAX_FOOD_ROWS = 6;
    private static final int MAX_TYPE_ROWS = 3;

    private static final Map<ExerciseCategoryEnum, String> CATEGORY_LABELS = Map.of(
            ExerciseCategoryEnum.AEROBIC, "有氧",
            ExerciseCategoryEnum.RESISTANCE, "力量",
            ExerciseCategoryEnum.FLEXIBILITY, "柔韧",
            ExerciseCategoryEnum.BALANCE, "平衡",
            ExerciseCategoryEnum.DAILY, "日常活动");

    /** 与 DietRuleEngine 的 flag code 对应的患者可读原因。 */
    private static final Map<String, String> FLAG_LABELS = Map.ofEntries(
            Map.entry("ALLERGEN_HIT", "含过敏原"),
            Map.entry("INTOLERANCE_HIT", "含不耐受食物"),
            Map.entry("LIMITED_FOOD", "吃了方案限制的食物"),
            Map.entry("MEAL_ENERGY_OVER", "单餐热量偏高"),
            Map.entry("MEAL_CARB_OVER", "单餐碳水偏高"),
            Map.entry("HIGH_GI_SHARE", "高 GI 主食偏多"),
            Map.entry("NO_VEGETABLE", "缺少蔬菜"),
            Map.entry("NO_PROTEIN", "缺少优质蛋白"),
            Map.entry("DAILY_ENERGY_OVER", "全天热量超标"),
            Map.entry("DAILY_ENERGY_LOW", "全天摄入偏少"),
            Map.entry("DAILY_CARB_OVER", "全天碳水超标"),
            Map.entry("DAILY_SODIUM_HIGH", "钠摄入偏高"));

    private final DietMealMapper dietMealMapper;
    private final DietMealItemMapper dietMealItemMapper;
    private final DietGoalMapper dietGoalMapper;
    private final ExerciseSessionMapper exerciseSessionMapper;
    private final ExercisePlanLoader exercisePlanLoader;

    /**
     * @param detailed 是否统计高频食物等明细（上期对比只需汇总数）
     * @param weekly 是否输出按周切块（月报 / 季报）
     */
    public Map<String, Object> build(
            String tenantId, String peopleId, LocalDate start, LocalDate end, boolean detailed, boolean weekly) {
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dayCount", days);
        out.put("diet", buildDiet(tenantId, peopleId, start, end, days, detailed, weekly));
        out.put("exercise", buildExercise(tenantId, peopleId, start, end, days, weekly));
        return out;
    }

    public Map<String, Object> build(String tenantId, String peopleId, LocalDate start, LocalDate end) {
        return build(tenantId, peopleId, start, end, true, false);
    }

    @SuppressWarnings("unchecked")
    public void attachPrev(Map<String, Object> cur, Map<String, Object> prev) {
        Map<String, Object> diet = (Map<String, Object>) cur.get("diet");
        Map<String, Object> prevDiet = (Map<String, Object>) prev.get("diet");
        diet.put("prev", pick(prevDiet, "recordDays", "completeDays", "avgKcal", "onTargetDays", "avgSodiumMg"));
        Map<String, Object> ex = (Map<String, Object>) cur.get("exercise");
        Map<String, Object> prevEx = (Map<String, Object>) prev.get("exercise");
        ex.put("prev", pick(prevEx, "activeDays", "totalMin", "activeMin", "avgWeeklyActiveMin"));
    }

    @SuppressWarnings("unchecked")
    public int dietCommentCount(Map<String, Object> lifestyle) {
        Object v = ((Map<String, Object>) lifestyle.get("diet")).get("staffCommentCount");
        return v instanceof Number n ? n.intValue() : 0;
    }

    private Map<String, Object> buildDiet(
            String tenantId,
            String peopleId,
            LocalDate start,
            LocalDate end,
            int days,
            boolean detailed,
            boolean weekly) {
        DietGoal goal = dietGoalMapper.findCurrent(tenantId, peopleId);
        Integer target = goal != null ? goal.getEnergyKcal() : null;
        int sodiumLimit = goal != null && goal.getSodiumMg() != null && goal.getSodiumMg() > 0
                ? goal.getSodiumMg()
                : EnergyTargetCalculator.SODIUM_LIMIT_MG;
        List<DietMeal> allMeals = dietMealMapper.listByPeopleRange(tenantId, peopleId, start, end);
        Map<LocalDate, List<DietMeal>> byDay = new TreeMap<>();
        for (DietMeal m : allMeals) {
            byDay.computeIfAbsent(m.getMealDate(), k -> new ArrayList<>()).add(m);
        }
        int completeDays = 0;
        int onTargetDays = 0;
        int highSodiumDays = 0;
        int breakfastDays = 0;
        int mealCount = 0;
        int redMeals = 0;
        int yellowMeals = 0;
        int commentCount = 0;
        double kcalSum = 0;
        double sodiumSum = 0;
        double carbSum = 0;
        double proteinSum = 0;
        double fatSum = 0;
        Map<String, int[]> flagCounts = new LinkedHashMap<>();
        Map<String, String> flagLevels = new HashMap<>();
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DietMeal>> e : byDay.entrySet()) {
            List<DietMeal> meals = e.getValue();
            boolean complete = mainMealCount(meals) == 3;
            double kcal = meals.stream().mapToDouble(m -> nz(m.getEnergyKcal())).sum();
            double sodium = meals.stream().mapToDouble(m -> nz(m.getSodiumMg())).sum();
            mealCount += meals.size();
            redMeals += (int) meals.stream().filter(m -> DietRuleEngine.RED.equals(m.getQualityLevel())).count();
            yellowMeals += (int) meals.stream().filter(m -> DietRuleEngine.YELLOW.equals(m.getQualityLevel())).count();
            commentCount += (int) meals.stream().filter(m -> StringUtils.hasText(m.getStaffComment())).count();
            if (meals.stream().anyMatch(m -> MealTypeEnum.BREAKFAST.name().equals(m.getMealType()))) {
                breakfastDays++;
            }
            for (DietMeal m : meals) {
                countFlags(m.getFlagsJson(), flagCounts, flagLevels);
            }
            if (complete) {
                completeDays++;
                kcalSum += kcal;
                sodiumSum += sodium;
                carbSum += meals.stream().mapToDouble(m -> nz(m.getCarbG())).sum();
                proteinSum += meals.stream().mapToDouble(m -> nz(m.getProteinG())).sum();
                fatSum += meals.stream().mapToDouble(m -> nz(m.getFatG())).sum();
                if (target != null && target > 0 && Math.abs(kcal - target) <= target * KCAL_TOLERANCE) {
                    onTargetDays++;
                }
                if (sodium > sodiumLimit) {
                    highSodiumDays++;
                }
            }
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", e.getKey().toString());
            day.put("meals", meals.size());
            day.put("complete", complete);
            day.put("kcal", Math.round(kcal));
            day.put("sodiumMg", Math.round(sodium));
            daily.add(day);
        }
        Map<String, Object> diet = new LinkedHashMap<>();
        diet.put("recordDays", byDay.size());
        diet.put("recordRate", rate(byDay.size(), days));
        diet.put("completeDays", completeDays);
        diet.put("mealCount", mealCount);
        diet.put("breakfastDays", breakfastDays);
        diet.put("targetKcal", target);
        diet.put("avgKcal", completeDays > 0 ? Math.round(kcalSum / completeDays) : null);
        diet.put("onTargetDays", target != null ? onTargetDays : null);
        diet.put("avgSodiumMg", completeDays > 0 ? Math.round(sodiumSum / completeDays) : null);
        diet.put("sodiumLimitMg", sodiumLimit);
        diet.put("highSodiumDays", highSodiumDays);
        diet.put("macro", completeDays > 0 ? macro(carbSum, proteinSum, fatSum, completeDays, goal) : null);
        diet.put("redMeals", redMeals);
        diet.put("yellowMeals", yellowMeals);
        diet.put("flagTop", flagRows(flagCounts, flagLevels));
        diet.put("staffCommentCount", commentCount);
        if (detailed) {
            diet.put("topFoods", topFoods(allMeals));
        }
        if (weekly) {
            diet.put("weekly", dietWeekly(start, end, daily));
        }
        diet.put("daily", daily);
        return diet;
    }

    /** 记全日的日均克数与供能比；目标比来自饮食目标克数。 */
    private static Map<String, Object> macro(
            double carb, double protein, double fat, int completeDays, DietGoal goal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avgCarbG", Math.round(carb / completeDays));
        m.put("avgProteinG", Math.round(protein / completeDays));
        m.put("avgFatG", Math.round(fat / completeDays));
        double total = carb * 4 + protein * 4 + fat * 9;
        m.put("carbPct", total > 0 ? Math.round(carb * 4 * 100 / total) : null);
        m.put("proteinPct", total > 0 ? Math.round(protein * 4 * 100 / total) : null);
        m.put("fatPct", total > 0 ? Math.round(fat * 9 * 100 / total) : null);
        if (goal != null && goal.getCarbG() != null && goal.getProteinG() != null && goal.getFatG() != null) {
            double t = goal.getCarbG() * 4d + goal.getProteinG() * 4d + goal.getFatG() * 9d;
            if (t > 0) {
                m.put("targetCarbPct", Math.round(goal.getCarbG() * 4d * 100 / t));
                m.put("targetProteinPct", Math.round(goal.getProteinG() * 4d * 100 / t));
                m.put("targetFatPct", Math.round(goal.getFatG() * 9d * 100 / t));
            }
        }
        return m;
    }

    private static void countFlags(String flagsJson, Map<String, int[]> counts, Map<String, String> levels) {
        if (!StringUtils.hasText(flagsJson)) {
            return;
        }
        JsonNode arr = JsonUtils.readTree(flagsJson);
        if (arr == null || !arr.isArray()) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode f : arr) {
            String code = f.path("code").asText("");
            if (code.isEmpty() || !seen.add(code)) {
                continue;
            }
            counts.computeIfAbsent(code, k -> new int[1])[0]++;
            levels.putIfAbsent(code, f.path("level").asText(null));
        }
    }

    private static List<Map<String, Object>> flagRows(Map<String, int[]> counts, Map<String, String> levels) {
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(MAX_FLAG_ROWS)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("code", e.getKey());
                    row.put("label", FLAG_LABELS.getOrDefault(e.getKey(), e.getKey()));
                    row.put("level", levels.get(e.getKey()));
                    row.put("count", e.getValue()[0]);
                    rows.add(row);
                });
        return rows;
    }

    /** 本期吃得最多的食物（按出现餐次），flaggedCount 为出现在需注意餐次里的次数。 */
    private List<Map<String, Object>> topFoods(List<DietMeal> meals) {
        if (meals.isEmpty()) {
            return List.of();
        }
        Map<String, DietMeal> mealById = new HashMap<>();
        meals.forEach(m -> mealById.put(m.getId(), m));
        Map<String, int[]> counts = new LinkedHashMap<>();
        for (DietMealItem item : dietMealItemMapper.listByMealIds(new ArrayList<>(mealById.keySet()))) {
            if (!StringUtils.hasText(item.getFoodName())) {
                continue;
            }
            int[] c = counts.computeIfAbsent(item.getFoodName().trim(), k -> new int[2]);
            c[0]++;
            DietMeal meal = mealById.get(item.getMealId());
            if (meal != null
                    && (DietRuleEngine.RED.equals(meal.getQualityLevel())
                            || DietRuleEngine.YELLOW.equals(meal.getQualityLevel()))) {
                c[1]++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(MAX_FOOD_ROWS)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", e.getKey());
                    row.put("count", e.getValue()[0]);
                    row.put("flaggedCount", e.getValue()[1]);
                    rows.add(row);
                });
        return rows;
    }

    private static List<Map<String, Object>> dietWeekly(
            LocalDate start, LocalDate end, List<Map<String, Object>> daily) {
        List<Map<String, Object>> weeks = HealthReportContentBuilder.weeklyBuckets(
                start, end, List.of(), List.of(), null, null);
        int[] recordDays = new int[weeks.size()];
        int[] completeDays = new int[weeks.size()];
        double[] kcal = new double[weeks.size()];
        for (Map<String, Object> day : daily) {
            int idx = (int) (ChronoUnit.DAYS.between(start, LocalDate.parse(String.valueOf(day.get("date")))) / 7);
            if (idx < 0 || idx >= weeks.size()) {
                continue;
            }
            recordDays[idx]++;
            if (Boolean.TRUE.equals(day.get("complete"))) {
                completeDays[idx]++;
                kcal[idx] += HealthReportContentBuilder.num(day.get("kcal"));
            }
        }
        for (int i = 0; i < weeks.size(); i++) {
            weeks.get(i).put("recordDays", recordDays[i]);
            weeks.get(i).put("avgKcal", completeDays[i] > 0 ? Math.round(kcal[i] / completeDays[i]) : null);
        }
        return weeks;
    }

    private Map<String, Object> buildExercise(
            String tenantId, String peopleId, LocalDate start, LocalDate end, int days, boolean weekly) {
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
        Map<String, int[]> byType = new LinkedHashMap<>();
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
                String type = StringUtils.hasText(s.getTypeName()) ? s.getTypeName().trim() : "其他";
                int[] t = byType.computeIfAbsent(type, k -> new int[2]);
                t[0]++;
                t[1] += min;
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
        List<Map<String, Object>> topTypes = new ArrayList<>();
        byType.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[1], a.getValue()[1]))
                .limit(MAX_TYPE_ROWS)
                .forEach(en -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", en.getKey());
                    t.put("count", en.getValue()[0]);
                    t.put("min", en.getValue()[1]);
                    topTypes.add(t);
                });
        int weeklyTarget = exercisePlanLoader.load(tenantId, peopleId, end).weeklyActiveMin();
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("activeDays", byDay.size());
        ex.put("recordRate", rate(byDay.size(), days));
        ex.put("sessionCount", sessionCount);
        ex.put("totalMin", totalMin);
        ex.put("activeMin", activeMin);
        ex.put("avgSessionMin", sessionCount > 0 ? Math.round((double) totalMin / sessionCount) : null);
        ex.put("avgWeeklyActiveMin", Math.round(activeMin * 7.0 / days));
        ex.put("weeklyActiveMinTarget", weeklyTarget > 0 ? weeklyTarget : null);
        ex.put("redCount", redCount);
        ex.put("yellowCount", yellowCount);
        ex.put("categories", categories);
        ex.put("topTypes", topTypes);
        if (weekly) {
            List<Map<String, Object>> weeks = HealthReportContentBuilder.weeklyBuckets(
                    start, end, daily, List.of("activeMin", "totalMin"), null, null);
            int met = 0;
            for (Map<String, Object> w : weeks) {
                long len = ChronoUnit.DAYS.between(
                                LocalDate.parse(String.valueOf(w.get("weekStart"))),
                                LocalDate.parse(String.valueOf(w.get("weekEnd"))))
                        + 1;
                // 月末不足 7 天的残周按天数折算目标
                Boolean ok = weeklyTarget > 0
                        ? HealthReportContentBuilder.num(w.get("activeMin")) >= weeklyTarget * len / 7.0
                        : null;
                w.put("met", ok);
                if (Boolean.TRUE.equals(ok)) {
                    met++;
                }
            }
            ex.put("weekly", weeks);
            ex.put("targetMetWeeks", weeklyTarget > 0 ? met : null);
            ex.put("weekCount", weeks.size());
        }
        ex.put("daily", daily);
        return ex;
    }

    private static Map<String, Object> pick(Map<String, Object> src, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (src == null) {
            return out;
        }
        for (String k : keys) {
            out.put(k, src.get(k));
        }
        return out;
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
