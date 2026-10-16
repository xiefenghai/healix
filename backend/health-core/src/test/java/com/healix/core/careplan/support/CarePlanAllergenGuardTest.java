package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.template.CarePlanTemplateRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarePlanAllergenGuardTest {

    private static final FoodAllergen EGG = new FoodAllergen("EGG", "鸡蛋", false);
    private static final FoodAllergen SHELLFISH = new FoodAllergen("SHELLFISH", "虾蟹贝类", false);
    private static final FoodAllergen LACTOSE = new FoodAllergen("LACTOSE", "乳糖", true);

    private static ObjectNode diet(String json) {
        return (ObjectNode) JsonUtils.readTree(json);
    }

    @Test
    void proteinWordIsNotEgg() {
        assertTrue(CarePlanAllergenGuard.firstHit("优质蛋白，适量瘦肉", List.of(EGG)).isEmpty());
        assertTrue(CarePlanAllergenGuard.firstHit("番茄炒蛋", List.of(EGG)).isPresent());
    }

    @Test
    void sanitizeRemovesHitSegmentsAndRecommended() {
        ObjectNode d = diet("""
                {"recommended":[{"code":"OATS","label":"燕麦"},{"code":"SEAFOOD","label":"清蒸虾"}],
                 "sampleDay":{"breakfast":"燕麦粥 + 水煮蛋 + 蔬菜","dinner":"白灼虾、蒜蓉西兰花","snacks":"蒸蛋羹"}}
                """);
        List<String> removed = CarePlanAllergenGuard.sanitize(d, List.of(EGG, SHELLFISH));
        assertEquals(4, removed.size());
        assertEquals(1, d.get("recommended").size());
        assertEquals("燕麦粥 + 蔬菜", d.at("/sampleDay/breakfast").asText());
        assertEquals("蒜蓉西兰花", d.at("/sampleDay/dinner").asText());
        assertEquals(CarePlanAllergenGuard.EMPTY_MEAL_HINT, d.at("/sampleDay/snacks").asText());
        assertTrue(CarePlanAllergenGuard.conflicts(d, List.of(EGG, SHELLFISH)).isEmpty());
    }

    @Test
    void negatedSegmentIsKept() {
        ObjectNode d = diet("""
                {"recommended":[],"sampleDay":{"lunch":"杂粮饭 + 瘦肉（避免虾蟹）"}}
                """);
        assertTrue(CarePlanAllergenGuard.sanitize(d, List.of(SHELLFISH)).isEmpty());
    }

    @Test
    void intoleranceOnlyFlaggedNotRemoved() {
        ObjectNode d = diet("""
                {"recommended":[{"code":"MILK","label":"低脂牛奶"}],"sampleDay":{}}
                """);
        assertTrue(CarePlanAllergenGuard.sanitize(d, List.of(LACTOSE)).isEmpty());
        List<CarePlanAllergenGuard.Hit> hits = CarePlanAllergenGuard.conflicts(d, List.of(LACTOSE));
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).allergen().intolerance());
    }

    @Test
    void enforceRebuildsAvoidAndKeepsManualEntries() {
        ObjectNode d = diet("""
                {"allergensAvoid":[{"code":"PEANUT","label":"花生","source":"ARCHIVE"},{"code":"KIWI","label":"猕猴桃"}]}
                """);
        CarePlanAllergenGuard.enforce(d, List.of(SHELLFISH, LACTOSE));
        JsonNode avoid = d.get("allergensAvoid");
        assertEquals(2, avoid.size());
        assertEquals("SHELLFISH", avoid.get(0).path("code").asText());
        assertEquals("ARCHIVE", avoid.get(0).path("source").asText());
        assertEquals("KIWI", avoid.get(1).path("code").asText());
        assertFalse(avoid.get(1).has("source"));
        assertEquals("LACTOSE", d.at("/intolerances/0/code").asText());
    }

    @Test
    void templateDietAvoidsAllergensWithAlternatives() {
        FoodAllergen fish = new FoodAllergen("FISH", "鱼类", false);
        FoodAllergen soy = new FoodAllergen("SOY", "大豆", false);
        FoodAllergen nut = new FoodAllergen("TREE_NUT", "坚果", false);
        List<FoodAllergen> allergens = List.of(EGG, fish, soy, nut, LACTOSE);
        CarePlanContextService.CarePlanContext ctx = new CarePlanContextService.CarePlanContext(
                List.of("diabetes"), allergens, AllergyStatusEnum.HAS, List.of(), List.of(), List.of(),
                false, false, JsonUtils.emptyObject());
        CarePlanTemplateRegistry registry = new CarePlanTemplateRegistry();
        ObjectNode diet = registry.generate(CarePlanTemplateKeyEnum.DIABETES, ctx, null, null).diet();
        assertTrue(CarePlanAllergenGuard.conflicts(diet, allergens).stream()
                .noneMatch(h -> !h.allergen().intolerance()));
        assertEquals("燕麦粥 + 瘦肉末 + 蔬菜", diet.at("/sampleDay/breakfast/text").asText());
        assertEquals("清淡主食 + 去皮禽肉 + 蔬菜", diet.at("/sampleDay/dinner/text").asText());
        assertEquals("瘦肉/禽肉", diet.at("/recommended/2/label").asText());
        assertEquals(4, diet.get("allergensAvoid").size());
        assertTrue(diet.path("notes").asText().contains("外出就餐"));
    }

    @Test
    void freeTextAllergenMatchesByName() {
        FoodAllergen pineapple = new FoodAllergen(null, "菠萝", false);
        ObjectNode d = diet("""
                {"recommended":[],"sampleDay":{"snacks":"菠萝 + 无糖酸奶"}}
                """);
        CarePlanAllergenGuard.sanitize(d, List.of(pineapple));
        assertEquals("无糖酸奶", d.at("/sampleDay/snacks").asText());
    }
}
