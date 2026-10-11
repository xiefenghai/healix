package com.healix.core.diet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.domain.DietMeal;
import com.healix.core.diet.domain.DietMealItem;
import com.healix.core.diet.dto.DietDayDto;
import com.healix.core.diet.dto.DietDaySummaryDto;
import com.healix.core.diet.dto.DietFlagDto;
import com.healix.core.diet.dto.DietMealDto;
import com.healix.core.diet.dto.DietMealItemDto;
import com.healix.core.diet.dto.FoodItemDto;
import com.healix.core.diet.dto.NutrientsDto;
import com.healix.core.diet.enums.DietSourceEnum;
import com.healix.core.diet.enums.MealTypeEnum;
import com.healix.core.diet.enums.OilLevelEnum;
import com.healix.core.diet.mapper.DietMealItemMapper;
import com.healix.core.diet.mapper.DietMealMapper;
import com.healix.core.diet.service.FoodCatalogService.FoodEntry;
import com.healix.core.diet.support.DietContextLoader;
import com.healix.core.diet.support.DietContextLoader.DietContext;
import com.healix.core.diet.support.DietGoalJson;
import com.healix.core.diet.support.DietRuleEngine;
import com.healix.core.diet.support.DietRuleEngine.RuleItem;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 饮食记录：餐次增删改查、营养重算、规则提示、DIET_LOG 自动打卡。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DietRecordService {

    public static final String DIET_LOG_TASK = "DIET_LOG";

    private static final int MAX_ITEMS = 30;
    private static final double MAX_ITEM_GRAMS = 3000;
    private static final int BACKFILL_DAYS = 30;
    private static final int MAX_SUMMARY_DAYS = 92;
    /** 估算项能量密度上限（纯脂肪约 900 kcal/100g） */
    private static final double MAX_KCAL_PER_GRAM = 9.0;
    private static final String PRECISION_ESTIMATE = "C";

    private final DietMealMapper mealMapper;
    private final DietMealItemMapper itemMapper;
    private final FoodCatalogService catalog;
    private final DietGoalService goalService;
    private final DietContextLoader contextLoader;
    private final DietRuleEngine ruleEngine;
    private final CarePlanCheckinService checkinService;

    /**
     * 明细输入。foodCode 命中食物库时营养由服务端按克数计算，客户端营养值忽略；
     * 未命中时为估算项，须给出名称、克数与能量。
     */
    public record MealItemInput(
            String foodCode,
            String foodName,
            Double amount,
            String unit,
            Double grams,
            String oilLevel,
            Double energyKcal,
            Double carbG,
            Double proteinG,
            Double fatG,
            Double fiberG,
            Double sodiumMg,
            Double confidence,
            Boolean gramsConfirmed) {}

    public record MealInput(
            LocalDate mealDate,
            String mealType,
            LocalDateTime eatenAt,
            String source,
            String note,
            List<MealItemInput> items) {}

    /** 录入人：患者本人或医护代录（二选一）。 */
    public record Actor(String peopleId, String staffId) {
        public static Actor patient(String peopleId) {
            return new Actor(peopleId, null);
        }

        public static Actor staff(String staffId) {
            return new Actor(null, staffId);
        }
    }

    public record FrequentFoodDto(FoodItemDto food, String unit, Double amount, long times) {}

    private record ComputedItem(DietMealItem row, RuleItem ruleItem) {}

    // ------------------------------------------------------------------ 写

    @Transactional
    public DietMealDto create(String tenantId, String peopleId, MealInput input, Actor actor) {
        DietMeal meal = new DietMeal();
        meal.setTenantId(tenantId);
        meal.setPeopleId(peopleId);
        meal.setRecordedByPeopleId(actor.peopleId());
        meal.setRecordedByStaffId(actor.staffId());
        EntityMeta.onCreate(meal);
        List<DietMealItem> items = fill(meal, input, actor);
        mealMapper.insert(meal);
        insertItems(meal, items);
        syncDietLog(tenantId, peopleId, meal.getMealDate());
        return toDto(meal, items);
    }

    @Transactional
    public DietMealDto update(String tenantId, String peopleId, String mealId, MealInput input, Actor actor) {
        DietMeal meal = requireMeal(tenantId, peopleId, mealId);
        LocalDate oldDate = meal.getMealDate();
        List<DietMealItem> items = fill(meal, input, actor);
        EntityMeta.onUpdate(meal);
        mealMapper.update(meal);
        itemMapper.softDeleteByMeal(meal.getId());
        insertItems(meal, items);
        syncDietLog(tenantId, peopleId, meal.getMealDate());
        if (!oldDate.equals(meal.getMealDate())) {
            syncDietLog(tenantId, peopleId, oldDate);
        }
        return toDto(meal, items);
    }

    @Transactional
    public void delete(String tenantId, String peopleId, String mealId) {
        DietMeal meal = requireMeal(tenantId, peopleId, mealId);
        mealMapper.softDelete(meal.getId());
        itemMapper.softDeleteByMeal(meal.getId());
        syncDietLog(tenantId, peopleId, meal.getMealDate());
    }

    @Transactional
    public DietMealDto comment(String tenantId, String peopleId, String mealId, String staffId, String comment) {
        DietMeal meal = requireMeal(tenantId, peopleId, mealId);
        String text = StringUtils.hasText(comment) ? comment.trim() : null;
        if (text != null && text.length() > 500) {
            throw new BusinessException(400, "点评不超过 500 字");
        }
        meal.setStaffComment(text);
        meal.setCommentStaffId(text == null ? null : staffId);
        meal.setCommentedAt(text == null ? null : LocalDateTime.now());
        EntityMeta.onUpdate(meal);
        mealMapper.updateComment(meal);
        return toDto(meal, itemMapper.listByMealIds(List.of(meal.getId())));
    }

    // ------------------------------------------------------------------ 读

    public DietMealDto get(String tenantId, String peopleId, String mealId) {
        DietMeal meal = requireMeal(tenantId, peopleId, mealId);
        return toDto(meal, itemMapper.listByMealIds(List.of(meal.getId())));
    }

    public DietDayDto day(String tenantId, String peopleId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        DietGoal goal = goalService.current(tenantId, peopleId);
        DietContext ctx = contextLoader.load(tenantId, peopleId);
        List<DietMeal> meals = mealMapper.listByPeopleRange(tenantId, peopleId, d, d);
        Map<String, List<DietMealItem>> itemsByMeal = loadItems(meals);

        NutrientsDto intake = NutrientsDto.ZERO;
        List<DietMealDto> mealDtos = new ArrayList<>();
        for (DietMeal m : meals) {
            intake = intake.plus(nutrientsOf(m));
            mealDtos.add(toDto(m, itemsByMeal.getOrDefault(m.getId(), List.of())));
        }
        Set<MealTypeEnum> logged = mainMealsLogged(meals);
        boolean complete = logged.size() == 3;

        DietDayDto out = new DietDayDto();
        out.setDate(d);
        out.setGoal(DietGoalJson.toDto(goal));
        out.setIntake(intake.rounded());
        if (goal != null && goal.getEnergyKcal() != null) {
            out.setRemainingKcal((int) Math.round(goal.getEnergyKcal() - intake.energyKcal()));
            Map<String, Integer> targets = new LinkedHashMap<>();
            for (MealTypeEnum t : List.of(MealTypeEnum.BREAKFAST, MealTypeEnum.LUNCH, MealTypeEnum.DINNER)) {
                targets.put(t.name(), (int) Math.round(goal.getEnergyKcal() * DietRuleEngine.mealShare(goal, t)));
            }
            out.setMealTargetsKcal(targets);
        }
        out.setMainMealsLogged(logged.stream().map(Enum::name).toList());
        out.setThreeMealsComplete(complete);
        out.setDayFlags(meals.isEmpty() ? List.of() : ruleEngine.evaluateDay(intake, complete, goal, ctx));
        out.setPrinciples(ctx.principles());
        out.setMeals(mealDtos);
        return out;
    }

    public List<DietDaySummaryDto> summary(String tenantId, String peopleId, LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw new BusinessException(400, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_SUMMARY_DAYS) {
            throw new BusinessException(400, "查询区间不超过 " + MAX_SUMMARY_DAYS + " 天");
        }
        DietGoal goal = goalService.current(tenantId, peopleId);
        Integer target = goal != null ? goal.getEnergyKcal() : null;
        Map<LocalDate, List<DietMeal>> byDay = new TreeMap<>(mealMapper.listByPeopleRange(tenantId, peopleId, start, end)
                .stream()
                .collect(Collectors.groupingBy(DietMeal::getMealDate)));
        List<DietDaySummaryDto> out = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DietMeal>> en : byDay.entrySet()) {
            List<DietMeal> ms = en.getValue();
            NutrientsDto intake = ms.stream().map(DietRecordService::nutrientsOf).reduce(NutrientsDto.ZERO, NutrientsDto::plus);
            int red = (int) ms.stream().filter(m -> DietRuleEngine.RED.equals(m.getQualityLevel())).count();
            int yellow = (int) ms.stream().filter(m -> DietRuleEngine.YELLOW.equals(m.getQualityLevel())).count();
            out.add(new DietDaySummaryDto(
                    en.getKey(), intake.rounded(), target, ms.size(), mainMealsLogged(ms).size() == 3, red, yellow));
        }
        return out;
    }

    public List<FrequentFoodDto> frequent(String tenantId, String peopleId, int limit) {
        int cap = Math.max(1, Math.min(limit, 30));
        List<DietMealItemMapper.FrequentFoodRow> rows =
                itemMapper.listFrequent(tenantId, peopleId, LocalDate.now().minusDays(60), cap);
        List<FrequentFoodDto> out = new ArrayList<>();
        for (DietMealItemMapper.FrequentFoodRow r : rows) {
            catalog.find(r.getFoodCode()).ifPresent(e -> out.add(new FrequentFoodDto(
                    catalog.get(e.item().getCode()), r.getUnit(), r.getAmount(), r.getTimes() == null ? 0 : r.getTimes())));
        }
        return out;
    }

    // ------------------------------------------------------------------ 计算

    private List<DietMealItem> fill(DietMeal meal, MealInput input, Actor actor) {
        if (input == null) {
            throw new BusinessException(400, "请求不能为空");
        }
        MealTypeEnum mealType = MealTypeEnum.parse(input.mealType());
        LocalDate date = input.mealDate() != null ? input.mealDate() : LocalDate.now();
        LocalDate today = LocalDate.now();
        if (date.isAfter(today)) {
            throw new BusinessException(400, "不能记录未来日期的饮食");
        }
        if (date.isBefore(today.minusDays(BACKFILL_DAYS))) {
            throw new BusinessException(400, "仅支持补记最近 " + BACKFILL_DAYS + " 天");
        }
        List<MealItemInput> inputs = input.items() == null ? List.of() : input.items();
        if (inputs.isEmpty()) {
            throw new BusinessException(400, "请至少添加一种食物");
        }
        if (inputs.size() > MAX_ITEMS) {
            throw new BusinessException(400, "一餐最多 " + MAX_ITEMS + " 种食物");
        }
        if (StringUtils.hasText(input.note()) && input.note().trim().length() > 200) {
            throw new BusinessException(400, "备注不超过 200 字");
        }

        List<ComputedItem> computed = new ArrayList<>();
        int order = 0;
        for (MealItemInput in : inputs) {
            computed.add(compute(meal.getTenantId(), in, order++));
        }
        NutrientsDto total = computed.stream()
                .map(c -> c.ruleItem().nutrients())
                .reduce(NutrientsDto.ZERO, NutrientsDto::plus);
        double estimatedKcal = computed.stream()
                .filter(c -> PRECISION_ESTIMATE.equals(c.row().getPrecisionLevel()))
                .mapToDouble(c -> c.ruleItem().nutrients().energyKcal())
                .sum();

        DietGoal goal = goalService.current(meal.getTenantId(), meal.getPeopleId());
        DietContext ctx = contextLoader.load(meal.getTenantId(), meal.getPeopleId());
        List<DietFlagDto> flags = ruleEngine.evaluateMeal(
                mealType, computed.stream().map(ComputedItem::ruleItem).toList(), total, goal, ctx);

        DietSourceEnum source = actor.staffId() != null ? DietSourceEnum.STAFF : DietSourceEnum.parseOrManual(input.source());
        meal.setMealDate(date);
        meal.setMealType(mealType.name());
        meal.setEatenAt(input.eatenAt());
        meal.setSource(source.name());
        NutrientsDto r = total.rounded();
        meal.setEnergyKcal(r.energyKcal());
        meal.setCarbG(r.carbG());
        meal.setProteinG(r.proteinG());
        meal.setFatG(r.fatG());
        meal.setFiberG(r.fiberG());
        meal.setSodiumMg(r.sodiumMg());
        meal.setEstimatedKcalRatio(total.energyKcal() > 0 ? round2(estimatedKcal / total.energyKcal()) : 0.0);
        meal.setQualityLevel(DietRuleEngine.qualityOf(flags));
        meal.setFlagsJson(JsonUtils.toJson(flags));
        meal.setNote(StringUtils.hasText(input.note()) ? input.note().trim() : null);
        return computed.stream().map(ComputedItem::row).toList();
    }

    private ComputedItem compute(String tenantId, MealItemInput in, int order) {
        DietMealItem row = new DietMealItem();
        row.setTenantId(tenantId);
        row.setSortOrder(order);
        row.setConfidence(in.confidence());
        row.setGramsConfirmed(Boolean.FALSE.equals(in.gramsConfirmed()) ? 0 : 1);
        row.setAmount(in.amount());
        row.setUnit(StringUtils.hasText(in.unit()) ? in.unit().trim() : "g");

        FoodEntry entry = StringUtils.hasText(in.foodCode()) ? catalog.require(in.foodCode()) : null;
        NutrientsDto n;
        String name;
        if (entry != null) {
            double grams = catalog.resolveGrams(entry, in.amount(), in.unit(), in.grams());
            validateGrams(grams, entry.item().getName());
            OilLevelEnum oil = entry.isDish() ? OilLevelEnum.parseOrNormal(in.oilLevel()) : null;
            n = catalog.nutrientsOf(entry, grams, oil);
            name = entry.item().getName();
            row.setFoodCode(entry.item().getCode());
            row.setGrams(round1(grams));
            row.setOilLevel(oil == null ? null : oil.name());
            row.setPrecisionLevel(entry.item().getPrecisionLevel());
        } else {
            name = StringUtils.hasText(in.foodName()) ? in.foodName().trim() : null;
            if (name == null) {
                throw new BusinessException(400, "请选择食物或填写名称");
            }
            if (name.length() > 50) {
                throw new BusinessException(400, "食物名称不超过 50 字");
            }
            double grams = in.grams() != null ? in.grams() : (in.amount() != null && isGramUnit(in.unit()) ? in.amount() : 0);
            validateGrams(grams, name);
            double kcal = nz(in.energyKcal());
            if (kcal <= 0) {
                throw new BusinessException(400, "「" + name + "」未在食物库中，请填写估算热量");
            }
            if (kcal > grams * MAX_KCAL_PER_GRAM) {
                throw new BusinessException(400, "「" + name + "」热量估算超出合理范围");
            }
            n = new NutrientsDto(kcal, nz(in.carbG()), nz(in.proteinG()), nz(in.fatG()), nz(in.fiberG()), nz(in.sodiumMg()));
            row.setGrams(round1(grams));
            row.setPrecisionLevel(PRECISION_ESTIMATE);
        }
        if (row.getAmount() == null || row.getAmount() <= 0) {
            row.setAmount(row.getGrams());
            row.setUnit("g");
        }
        NutrientsDto r = n.rounded();
        row.setFoodName(name);
        row.setEnergyKcal(r.energyKcal());
        row.setCarbG(r.carbG());
        row.setProteinG(r.proteinG());
        row.setFatG(r.fatG());
        row.setFiberG(r.fiberG());
        row.setSodiumMg(r.sodiumMg());
        return new ComputedItem(row, new RuleItem(name, entry, row.getGrams(), n));
    }

    private void insertItems(DietMeal meal, List<DietMealItem> items) {
        for (DietMealItem it : items) {
            it.setMealId(meal.getId());
            EntityMeta.onCreate(it);
        }
        itemMapper.insertBatch(items);
    }

    /** DIET_LOG 打卡失败不影响饮食保存。 */
    private void syncDietLog(String tenantId, String peopleId, LocalDate date) {
        try {
            List<DietMeal> meals = mealMapper.listByPeopleRange(tenantId, peopleId, date, date);
            checkinService.syncSystemCheckin(tenantId, peopleId, DIET_LOG_TASK, date, mainMealsLogged(meals).size() == 3);
        } catch (RuntimeException e) {
            log.warn("sync DIET_LOG checkin failed people={} date={}: {}", peopleId, date, e.getMessage());
        }
    }

    private static Set<MealTypeEnum> mainMealsLogged(List<DietMeal> meals) {
        Set<MealTypeEnum> set = EnumSet.noneOf(MealTypeEnum.class);
        for (DietMeal m : meals) {
            try {
                MealTypeEnum t = MealTypeEnum.valueOf(m.getMealType());
                if (t.isMainMeal()) {
                    set.add(t);
                }
            } catch (IllegalArgumentException ignored) {
                // 历史脏数据忽略
            }
        }
        return set;
    }

    private DietMeal requireMeal(String tenantId, String peopleId, String mealId) {
        DietMeal meal = mealMapper.findById(mealId);
        if (meal == null || !tenantId.equals(meal.getTenantId()) || !peopleId.equals(meal.getPeopleId())) {
            throw new BusinessException(404, "饮食记录不存在");
        }
        return meal;
    }

    private Map<String, List<DietMealItem>> loadItems(List<DietMeal> meals) {
        if (meals.isEmpty()) {
            return Map.of();
        }
        return itemMapper.listByMealIds(meals.stream().map(DietMeal::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(DietMealItem::getMealId));
    }

    // ------------------------------------------------------------------ DTO

    private static DietMealDto toDto(DietMeal m, List<DietMealItem> items) {
        DietMealDto d = new DietMealDto();
        d.setId(m.getId());
        d.setMealDate(m.getMealDate());
        d.setMealType(m.getMealType());
        d.setEatenAt(m.getEatenAt());
        d.setSource(m.getSource());
        d.setNutrients(nutrientsOf(m).rounded());
        d.setEstimatedKcalRatio(m.getEstimatedKcalRatio());
        d.setQualityLevel(m.getQualityLevel());
        d.setFlags(parseFlags(m.getFlagsJson()));
        d.setNote(m.getNote());
        d.setStaffComment(m.getStaffComment());
        d.setCommentedAt(m.getCommentedAt());
        d.setRecordedByStaff(StringUtils.hasText(m.getRecordedByStaffId()));
        d.setItems(items.stream().map(DietRecordService::toItemDto).toList());
        return d;
    }

    private static DietMealItemDto toItemDto(DietMealItem it) {
        DietMealItemDto d = new DietMealItemDto();
        d.setId(it.getId());
        d.setFoodCode(it.getFoodCode());
        d.setFoodName(it.getFoodName());
        d.setAmount(it.getAmount());
        d.setUnit(it.getUnit());
        d.setGrams(it.getGrams());
        d.setOilLevel(it.getOilLevel());
        d.setEnergyKcal(it.getEnergyKcal());
        d.setCarbG(it.getCarbG());
        d.setProteinG(it.getProteinG());
        d.setFatG(it.getFatG());
        d.setFiberG(it.getFiberG());
        d.setSodiumMg(it.getSodiumMg());
        d.setPrecisionLevel(it.getPrecisionLevel());
        d.setConfidence(it.getConfidence());
        d.setGramsConfirmed(it.getGramsConfirmed() == null || it.getGramsConfirmed() == 1);
        return d;
    }

    private static List<DietFlagDto> parseFlags(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<DietFlagDto> list = JsonUtils.fromJson(json, new TypeReference<List<DietFlagDto>>() {});
            return list == null ? List.of() : list;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static NutrientsDto nutrientsOf(DietMeal m) {
        return new NutrientsDto(
                nz(m.getEnergyKcal()), nz(m.getCarbG()), nz(m.getProteinG()),
                nz(m.getFatG()), nz(m.getFiberG()), nz(m.getSodiumMg()));
    }

    private static void validateGrams(double grams, String name) {
        if (grams <= 0 || grams > MAX_ITEM_GRAMS) {
            throw new BusinessException(400, "「" + name + "」的克数需在 0–" + (int) MAX_ITEM_GRAMS + " 之间");
        }
    }

    private static boolean isGramUnit(String unit) {
        return !StringUtils.hasText(unit) || "g".equalsIgnoreCase(unit.trim()) || "克".equals(unit.trim());
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
