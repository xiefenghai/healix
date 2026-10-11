package com.healix.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.diet.domain.FoodItem;
import com.healix.core.diet.domain.FoodRecipeItem;
import com.healix.core.diet.service.FoodCatalogService;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 平台食物库导入：seed/food_items.json 由 scripts/food-import/build_food_seed.py 生成。
 *
 * <p>同版本且条数一致时跳过；版本变化时 upsert 并下线旧版本独有条目。导入失败只记日志，不阻断启动。
 */
@Slf4j
@Component
@Order(70)
@RequiredArgsConstructor
public class FoodSeedRunner implements ApplicationRunner {

    private static final String SEED_PATH = "seed/food_items.json";

    private final FoodCatalogService catalogService;

    @Override
    public void run(ApplicationArguments args) {
        ClassPathResource resource = new ClassPathResource(SEED_PATH);
        if (!resource.exists()) {
            log.warn("Food seed {} not found, skip", SEED_PATH);
            return;
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = JsonUtils.mapper().readTree(in);
            String version = root.path("version").asText("");
            JsonNode foods = root.path("foods");
            if (version.isEmpty() || !foods.isArray()) {
                log.warn("Food seed malformed, skip");
                return;
            }
            if (catalogService.isSeedCurrent(version, foods.size())) {
                log.info("Food seed {} already imported ({} items)", version, foods.size());
                return;
            }
            List<FoodItem> items = new ArrayList<>(foods.size());
            List<FoodRecipeItem> recipe = new ArrayList<>();
            for (JsonNode f : foods) {
                items.add(toItem(f, version));
                int order = 0;
                for (JsonNode r : f.path("recipe")) {
                    FoodRecipeItem line = new FoodRecipeItem();
                    line.setDishCode(f.path("code").asText());
                    line.setIngredientCode(r.path("code").asText());
                    line.setGrams(r.path("grams").asDouble());
                    line.setOilAdjustable(r.path("oilAdjustable").asBoolean(false) ? 1 : 0);
                    line.setSortOrder(order++);
                    recipe.add(line);
                }
            }
            catalogService.importSeed(version, items, recipe);
            log.info("Food seed {} imported: {} items, {} recipe lines", version, items.size(), recipe.size());
        } catch (Exception e) {
            log.error("Food seed import failed: {}", e.getMessage(), e);
        }
    }

    private static FoodItem toItem(JsonNode f, String version) {
        FoodItem row = new FoodItem();
        row.setCode(f.path("code").asText());
        row.setName(f.path("name").asText());
        row.setAliasesJson(JsonUtils.toJson(f.path("aliases")));
        row.setKind(f.path("kind").asText("INGREDIENT"));
        row.setCategory(text(f, "category"));
        row.setSubCategory(text(f, "subCategory"));
        row.setEnergyKcal(num(f, "energyKcal"));
        row.setProteinG(num(f, "proteinG"));
        row.setFatG(num(f, "fatG"));
        row.setCarbG(num(f, "carbG"));
        row.setFiberG(num(f, "fiberG"));
        row.setSodiumMg(num(f, "sodiumMg"));
        row.setEdiblePct(num(f, "ediblePct"));
        row.setGi(f.hasNonNull("gi") ? (int) Math.round(f.get("gi").asDouble()) : null);
        row.setGiLevel(text(f, "giLevel"));
        row.setUnitsJson(JsonUtils.toJson(f.path("units")));
        row.setTagsJson(JsonUtils.toJson(f.path("tags")));
        row.setServingGrams(num(f, "servingGrams"));
        row.setIsDefault(f.path("isDefault").asBoolean(false) ? 1 : 0);
        row.setDataSource(text(f, "dataSource"));
        row.setPrecisionLevel(text(f, "precisionLevel"));
        row.setNeedsReview(f.path("needsReview").asBoolean(false) ? 1 : 0);
        row.setSeedVersion(version);
        row.setStatus("ACTIVE");
        EntityMeta.onCreate(row);
        return row;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Double num(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }
}
