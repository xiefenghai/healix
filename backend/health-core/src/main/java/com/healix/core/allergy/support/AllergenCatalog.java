package com.healix.core.allergy.support;

import com.healix.core.allergy.enums.AllergyCategoryEnum;
import com.healix.core.allergy.enums.AllergyTypeEnum;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 常见过敏原字典。药物按类别（交叉过敏以类为单位），食物的 {@link FoodMatch} 对齐食物库分类/二级分类，
 * 供饮食规则按成分命中而不是靠名称猜。
 */
public final class AllergenCatalog {

    /**
     * @param categories 命中的食物库一级分类
     * @param subCategoryKeywords 非空时，一级分类命中还需二级分类包含其一
     * @param nameKeywords 食物名称/别名包含其一即命中（兜底未归类的加工食品、菜肴）
     */
    public record FoodMatch(Set<String> categories, List<String> subCategoryKeywords, List<String> nameKeywords) {}

    public record Allergen(
            String code,
            AllergyCategoryEnum category,
            String label,
            List<String> aliases,
            AllergyTypeEnum defaultType,
            FoodMatch food) {

        /** 用于文本安全校验（如 Agent 回复）的关键词：名称 + 别名。 */
        public List<String> keywords() {
            List<String> out = new ArrayList<>();
            out.add(label);
            out.addAll(aliases);
            return out;
        }
    }

    private static final Map<String, Allergen> BY_CODE = new LinkedHashMap<>();

    static {
        drug("PENICILLIN", "青霉素类", "青霉素", "阿莫西林", "氨苄西林", "哌拉西林", "苄星青霉素");
        drug("CEPHALOSPORIN", "头孢类", "头孢");
        drug("SULFONAMIDE", "磺胺类", "磺胺", "复方新诺明");
        drug("NSAID", "阿司匹林/解热镇痛药", "阿司匹林", "布洛芬", "对乙酰氨基酚", "双氯芬酸", "吲哚美辛");
        drug("AMINOGLYCOSIDE", "链霉素/氨基糖苷类", "链霉素", "庆大霉素", "阿米卡星");
        drug("QUINOLONE", "喹诺酮类", "左氧氟沙星", "莫西沙星", "环丙沙星", "沙星");
        drug("MACROLIDE", "大环内酯类", "阿奇霉素", "红霉素", "克拉霉素");
        drug("IODINE_CONTRAST", "碘造影剂", "造影剂", "碘海醇");
        drug("LOCAL_ANESTHETIC", "局麻药", "利多卡因", "普鲁卡因");
        drug("INSULIN", "胰岛素制剂", "胰岛素");

        food("FISH", "鱼类", AllergyTypeEnum.ALLERGY, Set.of("AQUATIC"), List.of("鱼"), List.of("鱼"));
        food("SHELLFISH", "虾蟹贝类", AllergyTypeEnum.ALLERGY, Set.of("AQUATIC"), List.of("虾", "蟹", "贝"),
                List.of("虾", "蟹", "贝", "蛤", "蚝", "牡蛎", "蛏", "螺"));
        food("SEAFOOD", "海鲜（统称）", AllergyTypeEnum.ALLERGY, Set.of("AQUATIC"), List.of(),
                List.of("海鲜", "鱼", "虾", "蟹", "贝"), "海鲜", "水产");
        food("MILK", "牛奶/乳制品", AllergyTypeEnum.ALLERGY, Set.of("DAIRY"), List.of(),
                List.of("牛奶", "奶粉", "酸奶", "奶酪", "芝士", "奶油", "奶茶"), "牛奶", "乳制品");
        food("EGG", "鸡蛋", AllergyTypeEnum.ALLERGY, Set.of("EGG"), List.of(), List.of("蛋"), "蛋");
        food("PEANUT", "花生", AllergyTypeEnum.ALLERGY, Set.of(), List.of(), List.of("花生"));
        food("TREE_NUT", "坚果", AllergyTypeEnum.ALLERGY, Set.of("NUT"), List.of("树坚果"),
                List.of("核桃", "杏仁", "腰果", "榛子", "开心果", "松子", "碧根果", "夏威夷果"));
        food("SOY", "大豆", AllergyTypeEnum.ALLERGY, Set.of("SOY"), List.of("大豆"),
                List.of("黄豆", "豆腐", "豆浆", "腐竹", "豆干", "毛豆", "豆皮", "千张", "豆制品"), "黄豆");
        food("WHEAT", "小麦/麸质", AllergyTypeEnum.ALLERGY, Set.of("STAPLE"), List.of("小麦"),
                List.of("小麦", "面粉", "面条", "挂面", "馒头", "包子", "饺子", "面包", "烧饼", "油条", "麸"), "麸质");
        food("SESAME", "芝麻", AllergyTypeEnum.ALLERGY, Set.of(), List.of(), List.of("芝麻", "麻酱"));
        food("MANGO", "芒果", AllergyTypeEnum.ALLERGY, Set.of(), List.of(), List.of("芒果"));
        food("LACTOSE", "乳糖", AllergyTypeEnum.INTOLERANCE, Set.of("DAIRY"), List.of(),
                List.of("牛奶", "奶粉", "酸奶", "奶酪", "芝士", "奶茶"), "乳糖不耐受");

        env("POLLEN", "花粉");
        env("DUST_MITE", "尘螨", "螨虫");
        env("ANIMAL_DANDER", "动物皮毛", "猫毛", "狗毛");
        env("MOLD", "霉菌");
        env("LATEX", "乳胶", "橡胶手套");
        env("ALCOHOL_SKIN", "酒精（皮肤消毒）", "酒精");
        env("IODOPHOR", "碘伏/碘酒", "碘伏", "碘酒");
        env("ADHESIVE", "医用胶布", "胶布");
    }

    private AllergenCatalog() {}

    public static List<Allergen> all() {
        return List.copyOf(BY_CODE.values());
    }

    public static Optional<Allergen> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_CODE.get(code.trim()));
    }

    /** 按名称精确匹配字典项（名称或别名），用于自由录入/历史数据自动归码。 */
    public static Optional<Allergen> matchName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = name.trim();
        for (Allergen a : BY_CODE.values()) {
            if (a.label().equals(n) || a.aliases().contains(n)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    /** 条目的安全校验关键词：字典项取名称+别名，自由录入取录入名。 */
    public static Set<String> keywordsOf(String code, String name) {
        Set<String> out = new LinkedHashSet<>();
        find(code).ifPresent(a -> out.addAll(a.keywords()));
        if (name != null && !name.isBlank()) {
            out.add(name.trim());
        }
        return out;
    }

    private static void drug(String code, String label, String... aliases) {
        put(new Allergen(code, AllergyCategoryEnum.DRUG, label, List.of(aliases), AllergyTypeEnum.ALLERGY, null));
    }

    private static void env(String code, String label, String... aliases) {
        put(new Allergen(code, AllergyCategoryEnum.ENVIRONMENT, label, List.of(aliases), AllergyTypeEnum.ALLERGY, null));
    }

    private static void food(
            String code,
            String label,
            AllergyTypeEnum type,
            Set<String> categories,
            List<String> subKeywords,
            List<String> nameKeywords,
            String... aliases) {
        put(new Allergen(
                code,
                AllergyCategoryEnum.FOOD,
                label,
                List.of(aliases),
                type,
                new FoodMatch(categories, subKeywords, nameKeywords)));
    }

    private static void put(Allergen a) {
        BY_CODE.put(a.code(), a);
    }
}
