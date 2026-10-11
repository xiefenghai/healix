package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.careplan.dto.SafetyFlagDto;
import com.healix.core.careplan.support.CarePlanAllergenGuard.Hit;
import com.healix.core.careplan.enums.CarePlanSafetyLevelEnum;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CarePlanSafetyService {

    public static final String ERR_ALLERGEN = "DIET_ALLERGEN_CONFLICT";
    public static final String ERR_INCOMPLETE = "PLAN_INCOMPLETE";
    public static final String WARN_CONTEXT = "CONTEXT_INCOMPLETE";
    public static final String WARN_NO_DISEASE = "NO_DISEASE_TAG";
    public static final String WARN_HYPO = "HYPOGLYCEMIA_RISK";
    public static final String WARN_INTOLERANCE = "DIET_INTOLERANCE";
    public static final String WARN_ALLERGY_UNKNOWN = "ALLERGY_UNKNOWN";

    public List<SafetyFlagDto> evaluate(
            JsonNode exercise,
            JsonNode diet,
            JsonNode execution,
            boolean contextIncomplete,
            boolean noDiseaseTag,
            boolean hypoglycemiaRisk) {
        return evaluate(exercise, diet, execution, contextIncomplete, noDiseaseTag, hypoglycemiaRisk, List.of(), null);
    }

    /**
     * @param foodAllergens 过敏史中的食物过敏/不耐受，用于全文命中推荐食物与示例食谱
     * @param allergyStatus 过敏史状态；UNKNOWN（未询问）时提示医护先采集
     */
    public List<SafetyFlagDto> evaluate(
            JsonNode exercise,
            JsonNode diet,
            JsonNode execution,
            boolean contextIncomplete,
            boolean noDiseaseTag,
            boolean hypoglycemiaRisk,
            List<FoodAllergen> foodAllergens,
            AllergyStatusEnum allergyStatus) {
        List<SafetyFlagDto> flags = new ArrayList<>();
        if (!isExercisePresent(exercise) || !isDietPresent(diet) || !hasEnabledTask(execution)) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.ERROR.name(),
                    ERR_INCOMPLETE,
                    "方案三块需完整：运动、饮食，且执行计划至少 1 条启用任务"));
        }
        List<String> allergyHits = new ArrayList<>();
        List<String> intoleranceHits = new ArrayList<>();
        for (Hit hit : CarePlanAllergenGuard.conflicts(diet, foodAllergens)) {
            String desc = hit.where() + "「" + hit.text() + "」含" + hit.allergen().name();
            (hit.allergen().intolerance() ? intoleranceHits : allergyHits).add(desc);
        }
        SafetyFlagDto allergen = allergenConflict(diet);
        if (!allergyHits.isEmpty()) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.ERROR.name(),
                    ERR_ALLERGEN,
                    "饮食与过敏史冲突：" + String.join("；", allergyHits)));
        } else if (allergen != null) {
            flags.add(allergen);
        }
        if (!intoleranceHits.isEmpty()) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.WARN.name(),
                    WARN_INTOLERANCE,
                    "含患者不耐受食物，请确认食用量：" + String.join("；", intoleranceHits)));
        }
        if (allergyStatus == AllergyStatusEnum.UNKNOWN) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.WARN.name(),
                    WARN_ALLERGY_UNKNOWN,
                    "患者过敏史未采集，饮食建议无法排除过敏风险，建议先在档案中确认"));
        }
        if (contextIncomplete) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.WARN.name(),
                    WARN_CONTEXT,
                    "患者档案或关键指标不完整，方案基于有限上下文生成"));
        }
        if (noDiseaseTag) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.WARN.name(), WARN_NO_DISEASE, "未识别明确病种，已使用通用模板"));
        }
        if (hypoglycemiaRisk) {
            flags.add(new SafetyFlagDto(
                    CarePlanSafetyLevelEnum.WARN.name(),
                    WARN_HYPO,
                    "存在低血糖相关风险信号，请审阅运动强度与注意事项"));
        }
        return flags;
    }

    public void assertPublishable(List<SafetyFlagDto> flags, List<String> ackWarnCodes) {
        Set<String> acked = new HashSet<>();
        if (ackWarnCodes != null) {
            ackWarnCodes.stream().filter(StringUtils::hasText).forEach(acked::add);
        }
        List<String> errors = new ArrayList<>();
        List<String> unackedWarns = new ArrayList<>();
        for (SafetyFlagDto flag : flags) {
            if (CarePlanSafetyLevelEnum.ERROR.name().equals(flag.level())) {
                errors.add(flag.code() + ": " + flag.message());
            } else if (CarePlanSafetyLevelEnum.WARN.name().equals(flag.level())
                    && !acked.contains(flag.code())) {
                unackedWarns.add(flag.code());
            }
        }
        if (!errors.isEmpty()) {
            throw new com.healix.common.exception.BusinessException(
                    "无法发布：" + String.join("；", errors));
        }
        if (!unackedWarns.isEmpty()) {
            throw new com.healix.common.exception.BusinessException(
                    "请确认警告后发布：" + String.join(", ", unackedWarns));
        }
    }

    private SafetyFlagDto allergenConflict(JsonNode diet) {
        if (diet == null || diet.isNull()) {
            return null;
        }
        Set<String> avoid = codesOf(diet.get("allergensAvoid"));
        Set<String> recommended = codesOf(diet.get("recommended"));
        avoid.retainAll(recommended);
        if (avoid.isEmpty()) {
            return null;
        }
        return new SafetyFlagDto(
                CarePlanSafetyLevelEnum.ERROR.name(),
                ERR_ALLERGEN,
                "推荐食物与过敏原冲突：" + String.join(", ", avoid));
    }

    private Set<String> codesOf(JsonNode arr) {
        Set<String> out = new HashSet<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            if (n == null || n.isNull()) {
                continue;
            }
            if (n.isTextual()) {
                String c = normalizeCode(n.asText());
                if (StringUtils.hasText(c)) {
                    out.add(c);
                }
            } else if (n.hasNonNull("code")) {
                String c = normalizeCode(n.get("code").asText());
                if (StringUtils.hasText(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    public static String normalizeCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private boolean isExercisePresent(JsonNode exercise) {
        return exercise != null
                && !exercise.isNull()
                && StringUtils.hasText(text(exercise, "goal"));
    }

    private boolean isDietPresent(JsonNode diet) {
        if (diet == null || diet.isNull()) {
            return false;
        }
        JsonNode principles = diet.get("principles");
        return principles != null && principles.isArray() && !principles.isEmpty();
    }

    private boolean hasEnabledTask(JsonNode execution) {
        if (execution == null || execution.isNull()) {
            return false;
        }
        JsonNode tasks = execution.get("tasks");
        if (tasks == null || !tasks.isArray()) {
            return false;
        }
        for (JsonNode t : tasks) {
            if (t != null && t.path("enabled").asBoolean(true) && StringUtils.hasText(text(t, "title"))) {
                return true;
            }
        }
        return false;
    }

    private String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    public String toFlagsJson(List<SafetyFlagDto> flags) {
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        for (SafetyFlagDto f : flags) {
            ObjectNode o = arr.addObject();
            o.put("level", f.level());
            o.put("code", f.code());
            o.put("message", f.message());
        }
        return JsonUtils.toJson(arr);
    }

}
