package com.healix.core.diet.support;

import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.allergy.support.AllergenCatalog;
import com.healix.core.allergy.support.AllergenCatalog.Allergen;
import com.healix.core.allergy.support.AllergenCatalog.FoodMatch;
import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.domain.FoodRecipeItem;
import com.healix.core.diet.dto.DietFlagDto;
import com.healix.core.diet.dto.NutrientsDto;
import com.healix.core.diet.enums.MealTypeEnum;
import com.healix.core.diet.service.FoodCatalogService;
import com.healix.core.diet.service.FoodCatalogService.FoodEntry;
import com.healix.core.diet.support.DietContextLoader.DietContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 饮食规则：基于病种、过敏原、方案限制项与热量目标给出确定性提示，不调用模型。
 *
 * <p>餐次级规则在保存时计算并快照；日级规则在读取时计算（随当日记录变化）。
 */
@Component
@RequiredArgsConstructor
public class DietRuleEngine {

    public static final String RED = "RED";
    public static final String YELLOW = "YELLOW";
    public static final String INFO = "INFO";
    public static final String GREEN = "GREEN";

    private static final double MEAL_OVER_RATIO = 1.3;
    private static final double MEAL_CARB_OVER_RATIO = 1.2;
    private static final double DAY_OVER_RATIO = 1.1;
    private static final double HIGH_GI_SHARE = 0.5;

    private final FoodCatalogService catalog;

    /** 参与规则计算的一条明细；entry 为空表示未命中食物库的估算项。 */
    public record RuleItem(String name, FoodEntry entry, double grams, NutrientsDto nutrients) {}

    public List<DietFlagDto> evaluateMeal(
            MealTypeEnum mealType, List<RuleItem> items, NutrientsDto total, DietGoal goal, DietContext ctx) {
        List<DietFlagDto> flags = new ArrayList<>();
        checkAllergens(items, ctx, flags);
        checkLimited(items, ctx, flags);

        if (goal != null && goal.getEnergyKcal() != null && mealType.isMainMeal()) {
            double share = mealShare(goal, mealType);
            double target = goal.getEnergyKcal() * share;
            if (target > 0 && total.energyKcal() > target * MEAL_OVER_RATIO) {
                flags.add(new DietFlagDto(
                        "MEAL_ENERGY_OVER",
                        YELLOW,
                        String.format("本餐约 %.0f kcal，超出%s建议（约 %.0f kcal）较多", total.energyKcal(), mealType.label(), target)));
            }
            if (ctx.diabetes() && goal.getCarbG() != null) {
                double carbTarget = goal.getCarbG() * share;
                if (carbTarget > 0 && total.carbG() > carbTarget * MEAL_CARB_OVER_RATIO) {
                    flags.add(new DietFlagDto(
                            "MEAL_CARB_OVER",
                            YELLOW,
                            String.format("本餐碳水约 %.0f g，高于建议的 %.0f g，注意餐后血糖", total.carbG(), carbTarget)));
                }
            }
        }
        if (ctx.diabetes()) {
            checkHighGi(items, flags);
        }
        if (mealType.isMainMeal() && !items.isEmpty()) {
            if (items.stream().noneMatch(i -> containsCategory(i.entry(), "VEGETABLE"))) {
                flags.add(new DietFlagDto("NO_VEGETABLE", INFO, "这餐没有蔬菜，建议搭配一份绿叶菜"));
            }
            if (items.stream().noneMatch(i -> isProteinSource(i.entry()))) {
                flags.add(new DietFlagDto("NO_PROTEIN", INFO, "这餐缺少优质蛋白（蛋、奶、豆、鱼、瘦肉）"));
            }
        }
        return flags;
    }

    public List<DietFlagDto> evaluateDay(NutrientsDto intake, boolean threeMealsComplete, DietGoal goal, DietContext ctx) {
        List<DietFlagDto> flags = new ArrayList<>();
        if (goal != null && goal.getEnergyKcal() != null && goal.getEnergyKcal() > 0) {
            int target = goal.getEnergyKcal();
            if (intake.energyKcal() > target * DAY_OVER_RATIO) {
                flags.add(new DietFlagDto(
                        "DAILY_ENERGY_OVER",
                        YELLOW,
                        String.format("今日摄入约 %.0f kcal，已超过目标 %d kcal", intake.energyKcal(), target)));
            }
            int floor = "MALE".equalsIgnoreCase(goal.getBasisGender())
                    ? EnergyTargetCalculator.MALE_FLOOR_KCAL
                    : EnergyTargetCalculator.FEMALE_FLOOR_KCAL;
            if (threeMealsComplete && intake.energyKcal() < floor * 0.8) {
                flags.add(new DietFlagDto(
                        "DAILY_ENERGY_LOW",
                        YELLOW,
                        String.format("三餐合计仅约 %.0f kcal，摄入偏少，注意避免过度节食", intake.energyKcal())));
            }
            if (ctx.diabetes() && goal.getCarbG() != null && intake.carbG() > goal.getCarbG() * DAY_OVER_RATIO) {
                flags.add(new DietFlagDto(
                        "DAILY_CARB_OVER",
                        YELLOW,
                        String.format("今日碳水约 %.0f g，超过目标 %d g", intake.carbG(), goal.getCarbG())));
            }
        }
        int sodiumLimit = goal != null && goal.getSodiumMg() != null ? goal.getSodiumMg() : EnergyTargetCalculator.SODIUM_LIMIT_MG;
        if (intake.sodiumMg() > sodiumLimit) {
            flags.add(new DietFlagDto(
                    "DAILY_SODIUM_HIGH",
                    ctx.hypertension() ? YELLOW : INFO,
                    String.format("今日钠约 %.0f mg（约 %.1f g 盐），超过 %d mg", intake.sodiumMg(), intake.sodiumMg() * 2.54 / 1000, sodiumLimit)));
        }
        return flags;
    }

    /** RED > YELLOW > GREEN；INFO 不影响评级。 */
    public static String qualityOf(List<DietFlagDto> flags) {
        if (flags.stream().anyMatch(f -> RED.equals(f.level()))) {
            return RED;
        }
        if (flags.stream().anyMatch(f -> YELLOW.equals(f.level()))) {
            return YELLOW;
        }
        return GREEN;
    }

    public static double mealShare(DietGoal goal, MealTypeEnum mealType) {
        Map<String, Double> split = DietGoalJson.mealSplit(goal);
        Double v = split.get(mealType.name());
        return v == null ? 0 : v;
    }

    private void checkAllergens(List<RuleItem> items, DietContext ctx, List<DietFlagDto> flags) {
        if (ctx.allergens().isEmpty()) {
            return;
        }
        Set<String> allergyHits = new LinkedHashSet<>();
        Set<String> intoleranceHits = new LinkedHashSet<>();
        for (RuleItem item : items) {
            for (FoodAllergen allergen : ctx.allergens()) {
                if (matchesAllergen(item, allergen)) {
                    (allergen.intolerance() ? intoleranceHits : allergyHits)
                            .add(item.name() + "（" + allergen.name() + "）");
                }
            }
        }
        if (!allergyHits.isEmpty()) {
            flags.add(new DietFlagDto("ALLERGEN_HIT", RED, "可能含过敏原：" + String.join("、", allergyHits)));
        }
        if (!intoleranceHits.isEmpty()) {
            flags.add(new DietFlagDto(
                    "INTOLERANCE_HIT", YELLOW, "可能含不耐受食物：" + String.join("、", intoleranceHits) + "，注意食用量"));
        }
    }

    /** 有字典编码（或名称可归入字典）时按食物库分类命中，否则按名称包含匹配。 */
    private boolean matchesAllergen(RuleItem item, FoodAllergen allergen) {
        FoodMatch match = AllergenCatalog.find(allergen.code())
                .or(() -> AllergenCatalog.matchName(allergen.name()))
                .map(Allergen::food)
                .orElse(null);
        String a = FoodCatalogService.normalize(allergen.name());
        if (!a.isEmpty() && FoodCatalogService.normalize(item.name()).contains(a)) {
            return true;
        }
        if (match != null && containsAny(item.name(), match.nameKeywords())) {
            return true;
        }
        FoodEntry e = item.entry();
        if (e == null) {
            return false;
        }
        for (FoodEntry part : withIngredients(e)) {
            if (!a.isEmpty()
                    && (FoodCatalogService.normalize(part.item().getName()).contains(a)
                            || part.aliases().stream().anyMatch(al -> FoodCatalogService.normalize(al).contains(a)))) {
                return true;
            }
            if (match == null) {
                continue;
            }
            if (match.categories().contains(part.item().getCategory())
                    && (match.subCategoryKeywords().isEmpty()
                            || containsAny(part.item().getSubCategory(), match.subCategoryKeywords()))) {
                return true;
            }
            if (containsAny(part.item().getName(), match.nameKeywords())
                    || part.aliases().stream().anyMatch(al -> containsAny(al, match.nameKeywords()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return keywords.stream().anyMatch(text::contains);
    }

    private void checkLimited(List<RuleItem> items, DietContext ctx, List<DietFlagDto> flags) {
        if (ctx.limitedTags().isEmpty()) {
            return;
        }
        Set<String> hits = new LinkedHashSet<>();
        Set<String> rules = new LinkedHashSet<>();
        for (RuleItem item : items) {
            if (item.entry() == null) {
                continue;
            }
            for (FoodEntry part : withIngredients(item.entry())) {
                for (String tag : part.tags()) {
                    String rule = ctx.limitedTags().get(tag);
                    if (rule != null) {
                        hits.add(item.name());
                        rules.add(rule);
                    }
                }
            }
        }
        if (!hits.isEmpty()) {
            flags.add(new DietFlagDto(
                    "LIMITED_FOOD",
                    YELLOW,
                    String.join("、", hits) + " 属于管理方案中需限制的「" + String.join("；", rules) + "」"));
        }
    }

    private void checkHighGi(List<RuleItem> items, List<DietFlagDto> flags) {
        double known = 0;
        double high = 0;
        List<String> names = new ArrayList<>();
        for (RuleItem item : items) {
            if (item.entry() == null || item.entry().item().getGiLevel() == null) {
                continue;
            }
            known += item.nutrients().carbG();
            if ("HIGH".equals(item.entry().item().getGiLevel())) {
                high += item.nutrients().carbG();
                names.add(item.name());
            }
        }
        if (known > 0 && high / known > HIGH_GI_SHARE) {
            flags.add(new DietFlagDto(
                    "HIGH_GI_SHARE",
                    YELLOW,
                    "高 GI 主食占比较高（" + String.join("、", names) + "），可换部分杂粮或先吃菜后吃饭"));
        }
    }

    private boolean containsCategory(FoodEntry e, String category) {
        if (e == null) {
            return false;
        }
        return withIngredients(e).stream().anyMatch(p -> category.equals(p.item().getCategory()));
    }

    private boolean isProteinSource(FoodEntry e) {
        if (e == null) {
            return false;
        }
        return withIngredients(e).stream().anyMatch(p -> switch (String.valueOf(p.item().getCategory())) {
            case "MEAT", "AQUATIC", "EGG", "DAIRY", "SOY" -> true;
            default -> false;
        });
    }

    /** 菜肴展开为自身 + 菜谱食材；食材返回自身。 */
    private List<FoodEntry> withIngredients(FoodEntry e) {
        List<FoodEntry> out = new ArrayList<>();
        out.add(e);
        if (e.isDish() && e.recipe() != null) {
            for (FoodRecipeItem r : e.recipe()) {
                catalog.find(r.getIngredientCode()).ifPresent(out::add);
            }
        }
        return out;
    }
}
