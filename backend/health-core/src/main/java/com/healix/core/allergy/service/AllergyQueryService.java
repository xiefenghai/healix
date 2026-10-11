package com.healix.core.allergy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.domain.PeopleAllergy;
import com.healix.core.allergy.domain.PeopleAllergyProfile;
import com.healix.core.allergy.enums.AllergyCategoryEnum;
import com.healix.core.allergy.enums.AllergyClinicalStatusEnum;
import com.healix.core.allergy.enums.AllergyReactionEnum;
import com.healix.core.allergy.enums.AllergySeverityEnum;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.allergy.enums.AllergyTypeEnum;
import com.healix.core.allergy.enums.AllergyVerificationEnum;
import com.healix.core.allergy.mapper.PeopleAllergyMapper;
import com.healix.core.allergy.mapper.PeopleAllergyProfileMapper;
import com.healix.core.allergy.support.AllergenCatalog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 过敏史只读查询：安全校验、饮食规则、方案上下文统一从这里取，不做员工权限校验。
 *
 * <p>「生效」= 仍过敏（ACTIVE）且未被排除（非 REFUTED）；患者自述未确认的也算，宁可多提醒。
 */
@Service
@RequiredArgsConstructor
public class AllergyQueryService {

    /** 饮食规则用：code 为空表示自由录入，只能按名称匹配。 */
    public record FoodAllergen(String code, String name, boolean intolerance) {}

    private final PeopleAllergyMapper allergyMapper;
    private final PeopleAllergyProfileMapper profileMapper;

    public static boolean isEffective(PeopleAllergy row) {
        return AllergyClinicalStatusEnum.ACTIVE.name().equals(row.getClinicalStatus())
                && !AllergyVerificationEnum.REFUTED.name().equals(row.getVerification());
    }

    public List<PeopleAllergy> effective(String peopleId) {
        if (peopleId == null || peopleId.isBlank()) {
            return List.of();
        }
        return allergyMapper.listByPeopleId(peopleId).stream().filter(AllergyQueryService::isEffective).toList();
    }

    public List<String> activeNames(String peopleId) {
        return effective(peopleId).stream().map(PeopleAllergy::getAllergenName).distinct().toList();
    }

    /** 文本安全校验关键词：字典项展开为名称+别名（如「青霉素类」→ 阿莫西林）。不耐受不拦截。 */
    public List<String> safetyKeywords(String peopleId) {
        Set<String> out = new LinkedHashSet<>();
        for (PeopleAllergy row : effective(peopleId)) {
            if (AllergyTypeEnum.INTOLERANCE.name().equals(row.getAllergyType())) {
                continue;
            }
            out.addAll(AllergenCatalog.keywordsOf(row.getAllergenCode(), row.getAllergenName()));
        }
        return new ArrayList<>(out);
    }

    public List<FoodAllergen> foodAllergens(String peopleId) {
        List<FoodAllergen> out = new ArrayList<>();
        for (PeopleAllergy row : effective(peopleId)) {
            if (!AllergyCategoryEnum.FOOD.name().equals(row.getCategory())
                    && !AllergyCategoryEnum.OTHER.name().equals(row.getCategory())) {
                continue;
            }
            out.add(new FoodAllergen(
                    row.getAllergenCode(),
                    row.getAllergenName(),
                    AllergyTypeEnum.INTOLERANCE.name().equals(row.getAllergyType())));
        }
        return out;
    }

    public AllergyStatusEnum status(String tenantId, String peopleId) {
        List<PeopleAllergy> rows = allergyMapper.listByPeople(tenantId, peopleId);
        PeopleAllergyProfile profile = profileMapper.findByPeople(tenantId, peopleId);
        return resolveStatus(profile, rows);
    }

    /** 有生效条目即 HAS；否则以显式记录的 NKA 为准；其余一律视为未询问。 */
    public static AllergyStatusEnum resolveStatus(PeopleAllergyProfile profile, List<PeopleAllergy> rows) {
        if (rows.stream().anyMatch(AllergyQueryService::isEffective)) {
            return AllergyStatusEnum.HAS;
        }
        if (profile != null && AllergyStatusEnum.NKA.name().equals(profile.getStatus())) {
            return AllergyStatusEnum.NKA;
        }
        return AllergyStatusEnum.UNKNOWN;
    }

    /** 给模型的可读描述，如「青霉素类（药物过敏·重度·高危；过敏性休克）」。 */
    public List<String> describeForContext(String peopleId) {
        List<String> out = new ArrayList<>();
        for (PeopleAllergy row : effective(peopleId)) {
            StringBuilder sb = new StringBuilder(row.getAllergenName()).append("（");
            sb.append(categoryLabel(row.getCategory()));
            sb.append(AllergyTypeEnum.INTOLERANCE.name().equals(row.getAllergyType()) ? "不耐受" : "过敏");
            AllergySeverityEnum severity = safeSeverity(row.getSeverity());
            if (severity != AllergySeverityEnum.UNKNOWN) {
                sb.append("·").append(severity.label());
            }
            if (Integer.valueOf(1).equals(row.getHighRisk())) {
                sb.append("·高危");
            }
            List<String> reactions = reactionLabels(row.getReactionsJson());
            if (!reactions.isEmpty()) {
                sb.append("；").append(String.join("、", reactions));
            }
            if (AllergyVerificationEnum.UNCONFIRMED.name().equals(row.getVerification())) {
                sb.append("；患者自述未确认");
            }
            out.add(sb.append("）").toString());
        }
        return out;
    }

    public static List<String> reactionCodes(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        JsonNode arr = JsonUtils.readTree(json);
        if (arr != null && arr.isArray()) {
            arr.forEach(n -> {
                if (n.isTextual() && AllergyReactionEnum.parseOrNull(n.asText()) != null) {
                    out.add(n.asText());
                }
            });
        }
        return out;
    }

    public static List<String> reactionLabels(String json) {
        return reactionCodes(json).stream()
                .map(AllergyReactionEnum::parseOrNull)
                .map(AllergyReactionEnum::label)
                .toList();
    }

    private static String categoryLabel(String category) {
        try {
            return AllergyCategoryEnum.valueOf(category).label();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static AllergySeverityEnum safeSeverity(String severity) {
        try {
            return AllergySeverityEnum.valueOf(severity);
        } catch (RuntimeException e) {
            return AllergySeverityEnum.UNKNOWN;
        }
    }
}
