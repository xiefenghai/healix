package com.healix.core.careplan.guideline;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.core.assessment.catalog.AssessmentTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 条目适用 / 禁忌条件：各键之间为 AND，{@code diseaseTags} 须全部具备。
 *
 * <p>只支持方案上下文已有的事实；缺事实时视为不满足（适用条件不匹配、禁忌条件不命中）。
 */
public record GuidelineCondition(
        Set<String> diseaseTags,
        Boolean noDisease,
        Boolean hypoglycemiaRisk,
        Boolean fluidRestricted,
        Double bmiMin,
        Double bmiMax,
        Integer ageMin,
        Integer ageMax,
        String sex,
        Map<String, AssessmentTier> tierAtLeast,
        Boolean pregnantOrLactating,
        Boolean eatingDisorderRisk,
        Boolean exerciseRestricted,
        Set<String> highlights) {

    static final Set<String> KEYS = Set.of(
            "diseaseTags",
            "noDisease",
            "hypoglycemiaRisk",
            "fluidRestricted",
            "bmiMin",
            "bmiMax",
            "ageMin",
            "ageMax",
            "sex",
            "assessmentTierAtLeast",
            "pregnantOrLactating",
            "eatingDisorderRisk",
            "exerciseRestricted",
            "highlights");

    static final Set<String> DISEASES = Set.of("DIABETES", "HYPERTENSION", "OBESITY");

    public static final GuidelineCondition ALWAYS = new GuidelineCondition(
            Set.of(), null, null, null, null, null, null, null, null, Map.of(), null, null, null, Set.of());

    public boolean isEmpty() {
        return equals(ALWAYS);
    }

    public boolean matches(GuidelineFacts f) {
        Set<String> tags = f.diseaseTags() == null ? Set.of() : f.diseaseTags();
        if (!tags.containsAll(diseaseTags)) {
            return false;
        }
        if (noDisease != null && noDisease != tags.isEmpty()) {
            return false;
        }
        if (hypoglycemiaRisk != null && hypoglycemiaRisk != f.hypoglycemiaRisk()) {
            return false;
        }
        if (fluidRestricted != null && fluidRestricted != f.fluidRestricted()) {
            return false;
        }
        if ((bmiMin != null || bmiMax != null) && f.bmi() == null) {
            return false;
        }
        if (bmiMin != null && f.bmi() < bmiMin) {
            return false;
        }
        if (bmiMax != null && f.bmi() >= bmiMax) {
            return false;
        }
        if ((ageMin != null || ageMax != null) && f.age() == null) {
            return false;
        }
        if (ageMin != null && f.age() < ageMin) {
            return false;
        }
        if (ageMax != null && f.age() >= ageMax) {
            return false;
        }
        if (sex != null && !sex.equals(f.sex())) {
            return false;
        }
        for (Map.Entry<String, AssessmentTier> e : tierAtLeast.entrySet()) {
            AssessmentTier actual = f.tiers() == null ? null : f.tiers().get(e.getKey());
            if (actual == null || actual.rank() < e.getValue().rank()) {
                return false;
            }
        }
        if (pregnantOrLactating != null && pregnantOrLactating != f.pregnantOrLactating()) {
            return false;
        }
        if (eatingDisorderRisk != null && eatingDisorderRisk != f.eatingDisorderRisk()) {
            return false;
        }
        if (exerciseRestricted != null && exerciseRestricted != f.exerciseRestricted()) {
            return false;
        }
        if (!highlights.isEmpty()) {
            Set<String> actual = f.highlights() == null ? Set.of() : f.highlights();
            if (!actual.containsAll(highlights)) {
                return false;
            }
        }
        return true;
    }

    /** 中文描述，用于「为何排除」与条目展示。 */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (String d : diseaseTags) {
            parts.add("已患" + diseaseLabel(d));
        }
        if (noDisease != null) {
            parts.add(noDisease ? "档案未见糖尿病/高血压/肥胖" : "已患糖尿病/高血压/肥胖之一");
        }
        if (hypoglycemiaRisk != null) {
            parts.add(hypoglycemiaRisk ? "有低血糖风险" : "无低血糖风险");
        }
        if (fluidRestricted != null) {
            parts.add(fluidRestricted ? "需限水" : "饮水不受限");
        }
        if (bmiMin != null || bmiMax != null) {
            parts.add("BMI " + (bmiMin == null ? "" : "≥" + trim(bmiMin)) + (bmiMin != null && bmiMax != null ? " 且 " : "")
                    + (bmiMax == null ? "" : "<" + trim(bmiMax)));
        }
        if (ageMin != null || ageMax != null) {
            parts.add("年龄 " + (ageMin == null ? "" : "≥" + ageMin) + (ageMin != null && ageMax != null ? " 且 " : "")
                    + (ageMax == null ? "" : "<" + ageMax) + " 岁");
        }
        if (sex != null) {
            parts.add("MALE".equals(sex) ? "男性" : "女性");
        }
        tierAtLeast.forEach((d, t) -> parts.add(diseaseLabel(d) + "评估等级≥" + t.label()));
        if (pregnantOrLactating != null) {
            parts.add(pregnantOrLactating ? "孕期或哺乳期" : "非孕哺");
        }
        if (eatingDisorderRisk != null) {
            parts.add(eatingDisorderRisk ? "进食障碍风险" : "无进食障碍风险");
        }
        if (exerciseRestricted != null) {
            parts.add(exerciseRestricted ? "有运动禁忌/医生限制" : "无运动限制");
        }
        if (!highlights.isEmpty()) {
            parts.add("高亮命中 " + String.join("、", highlights));
        }
        return parts.isEmpty() ? "所有人" : String.join("、", parts);
    }

    static GuidelineCondition parse(JsonNode node, String where) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return ALWAYS;
        }
        if (!node.isObject()) {
            throw new IllegalStateException(where + " 须为对象");
        }
        node.fieldNames().forEachRemaining(k -> {
            if (!KEYS.contains(k)) {
                throw new IllegalStateException(where + " 不支持条件键 " + k);
            }
        });
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode t : node.path("diseaseTags")) {
            String d = t.asText("").trim().toUpperCase(Locale.ROOT);
            if (!DISEASES.contains(d)) {
                throw new IllegalStateException(where + " diseaseTags 未知病种 " + d);
            }
            tags.add(d);
        }
        Map<String, AssessmentTier> tiers = new LinkedHashMap<>();
        node.path("assessmentTierAtLeast").fields().forEachRemaining(e -> {
            String d = e.getKey().trim().toUpperCase(Locale.ROOT);
            AssessmentTier tier = AssessmentTier.parse(e.getValue().asText(null));
            if (!DISEASES.contains(d) || tier == null) {
                throw new IllegalStateException(where + " assessmentTierAtLeast 非法：" + e.getKey());
            }
            tiers.put(d, tier);
        });
        String sex = text(node, "sex");
        if (sex != null) {
            sex = sex.toUpperCase(Locale.ROOT);
            if (!"MALE".equals(sex) && !"FEMALE".equals(sex)) {
                throw new IllegalStateException(where + " sex 须为 MALE / FEMALE");
            }
        }
        Set<String> hl = new LinkedHashSet<>();
        for (JsonNode h : node.path("highlights")) {
            String code = h.asText("").trim();
            if (!code.isEmpty()) {
                hl.add(code);
            }
        }
        return new GuidelineCondition(
                Set.copyOf(tags),
                bool(node, "noDisease"),
                bool(node, "hypoglycemiaRisk"),
                bool(node, "fluidRestricted"),
                num(node, "bmiMin"),
                num(node, "bmiMax"),
                integer(node, "ageMin"),
                integer(node, "ageMax"),
                sex,
                Map.copyOf(tiers),
                bool(node, "pregnantOrLactating"),
                bool(node, "eatingDisorderRisk"),
                bool(node, "exerciseRestricted"),
                Set.copyOf(hl));
    }

    static String diseaseLabel(String code) {
        return switch (code) {
            case "DIABETES" -> "糖尿病";
            case "HYPERTENSION" -> "高血压";
            case "OBESITY" -> "肥胖";
            default -> code;
        };
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String text(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() || v.asText("").isBlank() ? null : v.asText().trim();
    }

    private static Boolean bool(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asBoolean();
    }

    private static Double num(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asDouble();
    }

    private static Integer integer(JsonNode n, String f) {
        JsonNode v = n.get(f);
        return v == null || v.isNull() ? null : v.asInt();
    }
}
