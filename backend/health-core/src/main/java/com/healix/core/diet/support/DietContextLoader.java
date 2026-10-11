package com.healix.core.diet.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.core.careplan.enums.CarePlanStatusEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.template.CarePlanTemplateRegistry;
import com.healix.core.allergy.service.AllergyQueryService;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 饮食规则所需的患者上下文：病种、过敏原、生效管理方案的饮食约束。
 *
 * <p>只读档案与方案，不加载检验/体征，避免每次保存餐次都走完整的方案上下文。
 */
@Component
@RequiredArgsConstructor
public class DietContextLoader {

    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final AllergyQueryService allergyQueryService;
    private final CarePlanMapper carePlanMapper;
    private final CarePlanVersionMapper versionMapper;
    private final CarePlanTemplateRegistry templateRegistry;

    /**
     * @param allergens 过敏史中的食物过敏/不耐受，加上方案「过敏原回避」里的名称
     * @param limitedTags 方案限制项：食物库标签（见 {@link #foodTagsFor}）→ 方案原文
     */
    public record DietContext(
            CarePlanTemplateKeyEnum diseaseKey,
            List<FoodAllergen> allergens,
            Map<String, String> limitedTags,
            List<String> principles) {

        public boolean diabetes() {
            return diseaseKey == CarePlanTemplateKeyEnum.DIABETES
                    || diseaseKey == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION;
        }

        public boolean hypertension() {
            return diseaseKey == CarePlanTemplateKeyEnum.HYPERTENSION
                    || diseaseKey == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION;
        }
    }

    public DietContext load(String tenantId, String peopleId) {
        CarePlanTemplateKeyEnum disease = templateRegistry.resolveTemplateKey(loadDiseaseCodes(tenantId, peopleId), null);
        List<FoodAllergen> allergens = new ArrayList<>(safeAllergens(peopleId));
        Map<String, String> limitedTags = new LinkedHashMap<>();
        List<String> principles = new ArrayList<>();

        JsonNode diet = loadActivePlanDiet(tenantId, peopleId);
        if (diet != null) {
            diet.path("principles").forEach(n -> {
                if (n.isTextual() && StringUtils.hasText(n.asText())) {
                    principles.add(n.asText().trim());
                }
            });
            diet.path("limited").forEach(n -> {
                String code = n.path("code").asText("").trim().toUpperCase(Locale.ROOT);
                String label = n.path("label").asText("");
                String text = StringUtils.hasText(label) ? label.trim() : code;
                foodTagsFor(code, label).forEach(tag -> limitedTags.putIfAbsent(tag, text));
            });
            diet.path("allergensAvoid").forEach(n -> {
                if (CarePlanAllergenGuard.SOURCE_ARCHIVE.equals(n.path("source").asText(null))) {
                    return;
                }
                String label = n.path("label").asText("").trim();
                if (StringUtils.hasText(label) && allergens.stream().noneMatch(a -> label.equals(a.name()))) {
                    allergens.add(new FoodAllergen(null, label, false));
                }
            });
        }
        return new DietContext(disease, List.copyOf(allergens), limitedTags, principles);
    }

    /**
     * 方案限制项 → 食物库标签。方案编码由模板或模型生成、不固定，先按编码再按标签文字兜底。
     */
    static Set<String> foodTagsFor(String code, String label) {
        Set<String> tags = new LinkedHashSet<>();
        String c = code == null ? "" : code;
        String l = label == null ? "" : label;
        if (c.contains("SUGAR") || c.contains("SWEET") || l.contains("糖") || l.contains("甜")) {
            tags.add("SUGARY_DRINK");
            tags.add("ADDED_SUGAR");
        }
        if (c.contains("PICKLE") || l.contains("腌") || l.contains("腊") || l.contains("咸菜")) {
            tags.add("PICKLED");
        }
        if (c.contains("PROCESSED_MEAT") || l.contains("加工肉") || l.contains("香肠") || l.contains("火腿")) {
            tags.add("PROCESSED_MEAT");
        }
        if (c.contains("FRIED") || l.contains("油炸") || l.contains("煎炸")) {
            tags.add("FRIED");
        }
        if (c.contains("HIGH_FAT") || l.contains("高脂") || l.contains("肥肉")) {
            tags.add("HIGH_FAT");
        }
        if (c.contains("ALCOHOL") || l.contains("酒")) {
            tags.add("ALCOHOL");
        }
        if (c.contains("SALT") || c.contains("SODIUM") || l.contains("高盐")) {
            tags.add("HIGH_SALT");
        }
        return tags;
    }

    private List<String> loadDiseaseCodes(String tenantId, String peopleId) {
        Set<String> codes = new LinkedHashSet<>();
        PeopleBasicArchive basic = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (basic != null && StringUtils.hasText(basic.getContentJson())) {
            JsonNode present = JsonUtils.readTree(basic.getContentJson()).get("presentIllness");
            if (present != null && present.isArray()) {
                present.forEach(n -> {
                    if (n.isTextual()) {
                        codes.add(n.asText());
                    }
                });
            }
        }
        List<PeopleDiseaseArchive> archives = diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId);
        if (archives != null) {
            for (PeopleDiseaseArchive a : archives) {
                if (StringUtils.hasText(a.getDiseaseCode())) {
                    codes.add(a.getDiseaseCode());
                }
            }
        }
        return new ArrayList<>(codes);
    }

    private List<FoodAllergen> safeAllergens(String peopleId) {
        try {
            return allergyQueryService.foodAllergens(peopleId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private JsonNode loadActivePlanDiet(String tenantId, String peopleId) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null
                || !CarePlanStatusEnum.ACTIVE.name().equals(plan.getStatus())
                || !StringUtils.hasText(plan.getCurrentVersionId())) {
            return null;
        }
        CarePlanVersion version = versionMapper.findById(plan.getCurrentVersionId());
        if (version == null || !StringUtils.hasText(version.getDietJson())) {
            return null;
        }
        return JsonUtils.readTree(version.getDietJson());
    }
}
