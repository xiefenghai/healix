package com.healix.core.diet.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.diet.domain.FoodItem;
import com.healix.core.diet.domain.FoodRecipeItem;
import com.healix.core.diet.dto.FoodItemDto;
import com.healix.core.diet.dto.FoodItemDto.FoodUnitDto;
import com.healix.core.diet.dto.FoodItemDto.RecipeLineDto;
import com.healix.core.diet.dto.NutrientsDto;
import com.healix.core.diet.enums.FoodKindEnum;
import com.healix.core.diet.enums.OilLevelEnum;
import com.healix.core.diet.mapper.FoodItemMapper;
import com.healix.core.diet.mapper.FoodRecipeItemMapper;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 食物库：平台食物常驻内存（约 2k 条），提供搜索、名称映射与营养计算。
 *
 * <p>营养计算以服务端为准：命中食物库的明细一律按库数据 × 克数重算，忽略前端传值。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FoodCatalogService {

    private static final int UPSERT_CHUNK = 500;

    private final FoodItemMapper foodItemMapper;
    private final FoodRecipeItemMapper recipeMapper;

    private volatile Catalog catalog;

    public record FoodEntry(
            FoodItem item,
            List<String> aliases,
            List<FoodUnitDto> units,
            Set<String> tags,
            List<FoodRecipeItem> recipe) {

        public boolean isDish() {
            return FoodKindEnum.DISH.name().equals(item.getKind());
        }

        public NutrientsDto per100g() {
            FoodItem f = item;
            return new NutrientsDto(
                    nz(f.getEnergyKcal()), nz(f.getCarbG()), nz(f.getProteinG()),
                    nz(f.getFatG()), nz(f.getFiberG()), nz(f.getSodiumMg()));
        }

        public Optional<Double> unitGrams(String unit) {
            if (!StringUtils.hasText(unit)) {
                return Optional.empty();
            }
            return units.stream().filter(u -> u.unit().equals(unit.trim())).map(FoodUnitDto::grams).findFirst();
        }
    }

    private record Catalog(Map<String, FoodEntry> byCode, Map<String, List<FoodEntry>> byNormName) {}

    // ------------------------------------------------------------------ 查询

    public Optional<FoodEntry> find(String code) {
        if (!StringUtils.hasText(code)) {
            return Optional.empty();
        }
        return Optional.ofNullable(catalog().byCode().get(code.trim()));
    }

    public FoodEntry require(String code) {
        return find(code).orElseThrow(() -> new BusinessException(400, "食物不存在：" + code));
    }

    public FoodItemDto get(String code) {
        return toDto(require(code));
    }

    public List<FoodItemDto> getMany(List<String> codes) {
        List<FoodItemDto> out = new ArrayList<>();
        for (String code : codes) {
            find(code).ifPresent(e -> out.add(toDto(e)));
        }
        return out;
    }

    public List<FoodItemDto> search(String keyword, int limit) {
        String q = normalize(keyword);
        if (q.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(limit, 50));
        record Scored(FoodEntry e, int score) {}
        List<Scored> hits = new ArrayList<>();
        for (FoodEntry e : catalog().byCode().values()) {
            int s = score(e, q);
            if (s > 0) {
                hits.add(new Scored(e, s));
            }
        }
        hits.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(h -> h.e().item().getName().length()));
        return hits.stream().limit(cap).map(h -> toDto(h.e())).toList();
    }

    /** 识别结果的名称映射：精确名/别名 → 包含关系；命中多条时优先菜肴与代表值。 */
    public Optional<FoodEntry> matchByName(String rawName) {
        String q = normalize(rawName);
        if (q.length() < 1) {
            return Optional.empty();
        }
        List<FoodEntry> exact = catalog().byNormName().get(q);
        if (exact != null && !exact.isEmpty()) {
            return exact.stream().max(Comparator.comparingInt(FoodCatalogService::preference));
        }
        if (q.length() < 2) {
            return Optional.empty();
        }
        FoodEntry best = null;
        int bestScore = 0;
        for (Map.Entry<String, List<FoodEntry>> en : catalog().byNormName().entrySet()) {
            String name = en.getKey();
            if (name.length() < 2) {
                continue;
            }
            int s;
            if (q.contains(name)) {
                s = 40 + name.length() * 4;
            } else if (name.contains(q)) {
                s = 30 + q.length() * 4 - (name.length() - q.length());
            } else {
                continue;
            }
            for (FoodEntry e : en.getValue()) {
                int total = s + preference(e);
                if (total > bestScore) {
                    bestScore = total;
                    best = e;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    // ------------------------------------------------------------------ 营养计算

    /** 按可食部克数计算；菜肴按油量档位调整菜谱中可调油脂部分。 */
    public NutrientsDto nutrientsOf(FoodEntry entry, double grams, OilLevelEnum oilLevel) {
        NutrientsDto base = entry.per100g().scale(grams / 100.0);
        if (!entry.isDish() || oilLevel == null || oilLevel == OilLevelEnum.NORMAL) {
            return base;
        }
        Double serving = entry.item().getServingGrams();
        if (serving == null || serving <= 0) {
            return base;
        }
        double portion = grams / serving;
        NutrientsDto delta = NutrientsDto.ZERO;
        for (FoodRecipeItem line : entry.recipe()) {
            if (line.getOilAdjustable() == null || line.getOilAdjustable() != 1) {
                continue;
            }
            FoodEntry oil = catalog().byCode().get(line.getIngredientCode());
            if (oil == null) {
                continue;
            }
            double oilGrams = nz(line.getGrams()) * portion * (oilLevel.factor() - 1.0);
            delta = delta.plus(oil.per100g().scale(oilGrams / 100.0));
        }
        return base.plus(delta);
    }

    /** 数量 + 单位 → 克数；单位为 g/克 时数量即克数。 */
    public double resolveGrams(FoodEntry entry, Double amount, String unit, Double gramsHint) {
        double amt = amount == null ? 0 : amount;
        if (!StringUtils.hasText(unit) || "g".equalsIgnoreCase(unit.trim()) || "克".equals(unit.trim())) {
            return amt > 0 ? amt : nz(gramsHint);
        }
        Optional<Double> per = entry == null ? Optional.empty() : entry.unitGrams(unit);
        if (per.isPresent()) {
            return amt * per.get();
        }
        if (gramsHint != null && gramsHint > 0) {
            return gramsHint;
        }
        throw new BusinessException(400, "无法换算单位「" + unit + "」，请改用克数");
    }

    // ------------------------------------------------------------------ 种子导入

    public boolean isSeedCurrent(String seedVersion, int expectedCount) {
        return foodItemMapper.countPlatformBySeedVersion(seedVersion) == expectedCount;
    }

    @Transactional
    public void importSeed(String seedVersion, List<FoodItem> foods, List<FoodRecipeItem> recipe) {
        for (int i = 0; i < foods.size(); i += UPSERT_CHUNK) {
            foodItemMapper.upsertBatch(foods.subList(i, Math.min(foods.size(), i + UPSERT_CHUNK)));
        }
        foodItemMapper.disableStalePlatform(seedVersion);
        List<String> dishCodes = foods.stream()
                .filter(f -> FoodKindEnum.DISH.name().equals(f.getKind()))
                .map(FoodItem::getCode)
                .toList();
        if (!dishCodes.isEmpty()) {
            recipeMapper.deleteByDishCodes(dishCodes);
        }
        for (int i = 0; i < recipe.size(); i += UPSERT_CHUNK) {
            recipeMapper.insertBatch(recipe.subList(i, Math.min(recipe.size(), i + UPSERT_CHUNK)));
        }
        catalog = null;
    }

    // ------------------------------------------------------------------ 内部

    private Catalog catalog() {
        Catalog c = catalog;
        if (c == null) {
            synchronized (this) {
                c = catalog;
                if (c == null) {
                    c = load();
                    catalog = c;
                }
            }
        }
        return c;
    }

    private Catalog load() {
        Map<String, List<FoodRecipeItem>> recipes = recipeMapper.listAll().stream()
                .collect(Collectors.groupingBy(FoodRecipeItem::getDishCode, LinkedHashMap::new, Collectors.toList()));
        Map<String, FoodEntry> byCode = new HashMap<>();
        Map<String, List<FoodEntry>> byNorm = new HashMap<>();
        for (FoodItem f : foodItemMapper.listActivePlatform()) {
            List<String> aliases = readList(f.getAliasesJson(), new TypeReference<List<String>>() {});
            List<FoodUnitDto> units = readList(f.getUnitsJson(), new TypeReference<List<FoodUnitDto>>() {});
            Set<String> tags = Set.copyOf(readList(f.getTagsJson(), new TypeReference<List<String>>() {}));
            FoodEntry e = new FoodEntry(f, aliases, units, tags, recipes.getOrDefault(f.getCode(), List.of()));
            byCode.put(f.getCode(), e);
            byNorm.computeIfAbsent(normalize(f.getName()), k -> new ArrayList<>()).add(e);
            for (String a : aliases) {
                byNorm.computeIfAbsent(normalize(a), k -> new ArrayList<>()).add(e);
            }
        }
        log.info("Food catalog loaded: {} foods, {} dishes with recipe", byCode.size(), recipes.size());
        return new Catalog(Map.copyOf(byCode), Map.copyOf(byNorm));
    }

    private static int score(FoodEntry e, String q) {
        int best = 0;
        best = Math.max(best, nameScore(normalize(e.item().getName()), q, 0));
        for (String a : e.aliases()) {
            best = Math.max(best, nameScore(normalize(a), q, 5));
        }
        if (best == 0) {
            return 0;
        }
        return best + preference(e);
    }

    private static int nameScore(String name, String q, int aliasPenalty) {
        if (name.equals(q)) {
            return 100 - aliasPenalty;
        }
        if (name.startsWith(q)) {
            return 80 - aliasPenalty;
        }
        if (name.contains(q)) {
            return 60 - aliasPenalty;
        }
        return 0;
    }

    /** 常用优先：代表值 > 有家常单位 > 菜肴；待复核与带品牌括注的靠后。 */
    private static int preference(FoodEntry e) {
        FoodItem f = e.item();
        int p = 0;
        if (f.getIsDefault() != null && f.getIsDefault() == 1) {
            p += 10;
        }
        if (!e.units().isEmpty()) {
            p += 6;
        }
        if (e.isDish()) {
            p += 4;
        }
        if (f.getNeedsReview() != null && f.getNeedsReview() == 1) {
            p -= 8;
        }
        if (f.getName().contains("牌")) {
            p -= 6;
        }
        return p;
    }

    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\s·・]", "");
    }

    private FoodItemDto toDto(FoodEntry e) {
        FoodItem f = e.item();
        List<RecipeLineDto> recipe = List.of();
        if (e.isDish()) {
            recipe = e.recipe().stream()
                    .map(r -> new RecipeLineDto(
                            r.getIngredientCode(),
                            Optional.ofNullable(catalog().byCode().get(r.getIngredientCode()))
                                    .map(x -> x.item().getName())
                                    .orElse(r.getIngredientCode()),
                            nz(r.getGrams()),
                            r.getOilAdjustable() != null && r.getOilAdjustable() == 1))
                    .toList();
        }
        return new FoodItemDto(
                f.getCode(),
                f.getName(),
                e.aliases(),
                f.getKind(),
                f.getCategory(),
                nz(f.getEnergyKcal()),
                f.getProteinG(),
                f.getFatG(),
                f.getCarbG(),
                f.getFiberG(),
                f.getSodiumMg(),
                f.getGi(),
                f.getGiLevel(),
                e.units(),
                e.tags().stream().sorted().toList(),
                f.getServingGrams(),
                f.getPrecisionLevel(),
                f.getDataSource(),
                f.getNeedsReview() != null && f.getNeedsReview() == 1,
                recipe);
    }

    private static <T> List<T> readList(String json, TypeReference<List<T>> type) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<T> list = JsonUtils.fromJson(json, type);
            return list == null ? List.of() : list;
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    static double nz(Double v) {
        return v == null ? 0 : v;
    }
}
