package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.allergy.support.AllergenCatalog;
import com.healix.core.allergy.support.AllergenCatalog.Allergen;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 管理方案饮食块的过敏原守卫：按过敏史强制写入 allergensAvoid / intolerances，
 * 并按食物关键词（含别名与常见菜名）检查推荐食物与示例食谱。
 *
 * <p>只看 recommended 与 sampleDay：principles / limited / notes 里出现过敏原多半是「避免 xx」，不算推荐。
 */
public final class CarePlanAllergenGuard {

    public static final String SOURCE_ARCHIVE = "ARCHIVE";

    /** 命中位置 + 原文 + 命中的过敏原。 */
    public record Hit(String where, String text, FoodAllergen allergen) {}

    /** 含过敏原字样但并非该食物的常见词，匹配前剔除，避免「优质蛋白」误判鸡蛋。 */
    private static final List<String> TEXT_EXCLUDES =
            List.of("蛋白", "贝贝南瓜", "螺旋藻", "无过敏时", "如无过敏", "不过敏");

    /** 片段里出现这些词说明是在叮嘱避开，而不是推荐。 */
    private static final List<String> NEGATIONS =
            List.of("避免", "忌", "不吃", "禁", "不含", "勿", "不宜", "不要", "过敏", "替代", "代替", "除外");

    private static final Pattern SEGMENT = Pattern.compile("[^+＋、，,；;/／]+|[+＋、，,；;/／]");

    private static final Map<String, String> MEAL_LABELS =
            Map.of("breakfast", "早餐", "lunch", "午餐", "dinner", "晚餐", "snacks", "加餐");

    public static final String EMPTY_MEAL_HINT = "按过敏史选择替代食物";

    private CarePlanAllergenGuard() {}

    /** 文本命中关键词：字典项取名称 + 别名 + 食物名关键词，自由录入取录入名。 */
    public static Set<String> keywords(FoodAllergen a) {
        Set<String> out = new LinkedHashSet<>();
        resolve(a).ifPresent(dict -> {
            out.add(dict.label());
            out.addAll(dict.aliases());
            if (dict.food() != null) {
                out.addAll(dict.food().nameKeywords());
            }
        });
        if (StringUtils.hasText(a.name())) {
            out.add(a.name().trim());
        }
        out.removeIf(k -> !StringUtils.hasText(k));
        return out;
    }

    public static String codeOf(FoodAllergen a) {
        return resolve(a).map(Allergen::code).orElseGet(() -> CarePlanSafetyService.normalizeCode(a.name()));
    }

    public static Optional<FoodAllergen> firstHit(String text, List<FoodAllergen> allergens) {
        if (!StringUtils.hasText(text) || allergens == null || allergens.isEmpty()) {
            return Optional.empty();
        }
        String cleaned = text;
        for (String ex : TEXT_EXCLUDES) {
            cleaned = cleaned.replace(ex, " ");
        }
        for (FoodAllergen a : allergens) {
            for (String k : keywords(a)) {
                if (cleaned.contains(k)) {
                    return Optional.of(a);
                }
            }
        }
        return Optional.empty();
    }

    /** 片段级判断：叮嘱「避免 xx」的片段不算命中。 */
    public static Optional<FoodAllergen> segmentHit(String segment, List<FoodAllergen> allergens) {
        if (!StringUtils.hasText(segment) || NEGATIONS.stream().anyMatch(segment::contains)) {
            return Optional.empty();
        }
        return firstHit(segment, allergens);
    }

    /**
     * 按过敏史重建 allergensAvoid（真过敏）与 intolerances（不耐受）。
     * 来自档案的条目带 source=ARCHIVE，档案里已删除/已排除的随之移除；医护手填的条目保留。
     */
    public static void enforce(ObjectNode diet, List<FoodAllergen> allergens) {
        if (diet == null) {
            return;
        }
        List<FoodAllergen> list = allergens == null ? List.of() : allergens;
        rebuild(diet, "allergensAvoid", list.stream().filter(a -> !a.intolerance()).toList());
        rebuild(diet, "intolerances", list.stream().filter(FoodAllergen::intolerance).toList());
    }

    private static void rebuild(ObjectNode diet, String field, List<FoodAllergen> fromArchive) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        Set<String> codes = new LinkedHashSet<>();
        Set<String> labels = new LinkedHashSet<>();
        for (FoodAllergen a : fromArchive) {
            String code = codeOf(a);
            if (code == null || !codes.add(code)) {
                continue;
            }
            labels.add(a.name().trim());
            ObjectNode n = out.addObject();
            n.put("code", code);
            n.put("label", a.name().trim());
            n.put("source", SOURCE_ARCHIVE);
        }
        JsonNode existing = diet.get(field);
        if (existing != null && existing.isArray()) {
            for (JsonNode n : existing) {
                if (n == null || SOURCE_ARCHIVE.equals(n.path("source").asText(null))) {
                    continue;
                }
                String label = n.isTextual() ? n.asText() : n.path("label").asText("");
                String code = n.isTextual()
                        ? CarePlanSafetyService.normalizeCode(label)
                        : CarePlanSafetyService.normalizeCode(n.path("code").asText(label));
                if (!StringUtils.hasText(label) && !StringUtils.hasText(code)) {
                    continue;
                }
                if ((code != null && codes.contains(code)) || labels.contains(label.trim())) {
                    continue;
                }
                if (code != null) {
                    codes.add(code);
                }
                ObjectNode copy = out.addObject();
                copy.put("code", code == null ? "" : code);
                copy.put("label", label.trim());
            }
        }
        diet.set(field, out);
    }

    /** 列出推荐食物与示例食谱中命中过敏史（含不耐受）的位置。 */
    public static List<Hit> conflicts(JsonNode diet, List<FoodAllergen> allergens) {
        List<Hit> hits = new ArrayList<>();
        if (diet == null || !diet.isObject() || allergens == null || allergens.isEmpty()) {
            return hits;
        }
        JsonNode recommended = diet.get("recommended");
        if (recommended != null && recommended.isArray()) {
            for (JsonNode n : recommended) {
                String label = foodLabel(n);
                recommendedHit(n, allergens).ifPresent(a -> hits.add(new Hit("推荐食物", label, a)));
            }
        }
        JsonNode sample = diet.get("sampleDay");
        if (sample != null && sample.isObject()) {
            sample.fields().forEachRemaining(e -> {
                String where = "示例食谱·" + MEAL_LABELS.getOrDefault(e.getKey(), e.getKey());
                for (String text : sampleMealTexts(e.getValue())) {
                    for (String seg : contentSegments(text)) {
                        segmentHit(seg, allergens).ifPresent(a -> hits.add(new Hit(where, seg.trim(), a)));
                    }
                }
            });
        }
        return hits;
    }

    /** 兼容字符串餐次与 `{text, swapHint}`。 */
    static List<String> sampleMealTexts(JsonNode meal) {
        List<String> out = new ArrayList<>();
        if (meal == null || meal.isNull()) {
            return out;
        }
        if (meal.isTextual()) {
            out.add(meal.asText());
            return out;
        }
        if (meal.isObject()) {
            if (meal.has("text") && meal.get("text").isTextual()) {
                out.add(meal.get("text").asText());
            }
            if (meal.has("swapHint") && meal.get("swapHint").isTextual()) {
                out.add(meal.get("swapHint").asText());
            }
        }
        return out;
    }

    /**
     * 清洗 AI/模板产出：移除命中真过敏的推荐食物和示例食谱片段（不耐受只提示不删除）。
     *
     * @return 被移除内容的可读描述
     */
    public static List<String> sanitize(ObjectNode diet, List<FoodAllergen> allergens) {
        List<String> removed = new ArrayList<>();
        if (diet == null || allergens == null) {
            return removed;
        }
        List<FoodAllergen> strict = allergens.stream().filter(a -> !a.intolerance()).toList();
        if (strict.isEmpty()) {
            return removed;
        }
        JsonNode recommended = diet.get("recommended");
        if (recommended != null && recommended.isArray()) {
            Iterator<JsonNode> it = recommended.elements();
            while (it.hasNext()) {
                JsonNode n = it.next();
                Optional<FoodAllergen> hit = recommendedHit(n, strict);
                if (hit.isPresent()) {
                    removed.add("推荐食物「" + foodLabel(n) + "」（" + hit.get().name() + "）");
                    it.remove();
                }
            }
        }
        sanitizeSampleMeals(diet.get("sampleDay"), "", strict, removed);
        JsonNode week = diet.get("sampleWeek");
        if (week instanceof ObjectNode weekObj) {
            List<String> days = new ArrayList<>();
            weekObj.fieldNames().forEachRemaining(days::add);
            for (String day : days) {
                sanitizeSampleMeals(weekObj.get(day), day + "·", strict, removed);
            }
        }
        return removed;
    }

    private static void sanitizeSampleMeals(
            JsonNode sample, String prefix, List<FoodAllergen> strict, List<String> removed) {
        if (!(sample instanceof ObjectNode sampleObj)) {
            return;
        }
        List<String> keys = new ArrayList<>();
        sampleObj.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            JsonNode v = sampleObj.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            String meal = prefix + MEAL_LABELS.getOrDefault(key, key);
            if (v.isTextual()) {
                String cleaned = stripSegments(v.asText(), strict, meal, removed);
                if (!cleaned.equals(v.asText())) {
                    sampleObj.put(key, cleaned);
                }
                continue;
            }
            if (v instanceof ObjectNode mealObj) {
                if (mealObj.has("text") && mealObj.get("text").isTextual()) {
                    String text = mealObj.get("text").asText();
                    String cleaned = stripSegments(text, strict, meal, removed);
                    if (!cleaned.equals(text)) {
                        mealObj.put("text", cleaned);
                    }
                }
                if (mealObj.has("swapHint") && mealObj.get("swapHint").isTextual()) {
                    String hint = mealObj.get("swapHint").asText();
                    String cleaned = stripSegments(hint, strict, meal + "·替换", removed);
                    if (!StringUtils.hasText(cleaned) || EMPTY_MEAL_HINT.equals(cleaned)) {
                        mealObj.putNull("swapHint");
                    } else if (!cleaned.equals(hint)) {
                        mealObj.put("swapHint", cleaned);
                    }
                }
            }
        }
    }

    /** 给模型的硬约束：过敏原名称 + 需要回避的关键词。 */
    public static ArrayNode promptGuard(List<FoodAllergen> allergens) {
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        if (allergens == null) {
            return arr;
        }
        for (FoodAllergen a : allergens) {
            ObjectNode n = arr.addObject();
            n.put("code", codeOf(a));
            n.put("name", a.name());
            n.put("type", a.intolerance() ? "INTOLERANCE" : "ALLERGY");
            ArrayNode kw = n.putArray("keywords");
            keywords(a).forEach(kw::add);
        }
        return arr;
    }

    private static Optional<FoodAllergen> recommendedHit(JsonNode n, List<FoodAllergen> allergens) {
        if (n == null || n.isNull()) {
            return Optional.empty();
        }
        String code = n.isTextual() ? null : CarePlanSafetyService.normalizeCode(n.path("code").asText(null));
        if (code != null) {
            for (FoodAllergen a : allergens) {
                if (code.equals(codeOf(a))) {
                    return Optional.of(a);
                }
            }
        }
        return segmentHit(foodLabel(n), allergens);
    }

    private static String foodLabel(JsonNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        return n.isTextual() ? n.asText() : n.path("label").asText(n.path("code").asText(""));
    }

    private static List<String> contentSegments(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = SEGMENT.matcher(text);
        while (m.find()) {
            String tok = m.group();
            if (!isSeparator(tok) && StringUtils.hasText(tok)) {
                out.add(tok);
            }
        }
        return out;
    }

    private static String stripSegments(String text, List<FoodAllergen> allergens, String meal, List<String> removed) {
        StringBuilder sb = new StringBuilder();
        String pendingSep = "";
        boolean changed = false;
        Matcher m = SEGMENT.matcher(text);
        while (m.find()) {
            String tok = m.group();
            if (isSeparator(tok)) {
                if (sb.length() > 0) {
                    pendingSep = tok;
                }
                continue;
            }
            Optional<FoodAllergen> hit = segmentHit(tok, allergens);
            if (hit.isPresent()) {
                removed.add(meal + "「" + tok.trim() + "」（" + hit.get().name() + "）");
                changed = true;
                continue;
            }
            if (sb.length() > 0) {
                sb.append(pendingSep.isEmpty() ? " + " : pendingSep);
            }
            sb.append(sb.length() > 0 ? tok : tok.stripLeading());
            pendingSep = "";
        }
        if (!changed) {
            return text;
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? EMPTY_MEAL_HINT : out;
    }

    private static boolean isSeparator(String tok) {
        return tok.length() == 1 && "+＋、，,；;/／".contains(tok);
    }

    private static Optional<Allergen> resolve(FoodAllergen a) {
        Optional<Allergen> byCode = AllergenCatalog.find(a.code());
        return byCode.isPresent() ? byCode : AllergenCatalog.matchName(a.name());
    }
}
