package com.healix.core.careplan.guideline;

import com.healix.core.assessment.catalog.AssessmentTier;
import java.util.Map;
import java.util.Set;

/**
 * 条目匹配用的患者事实，只取方案上下文已有字段。
 *
 * @param diseaseTags 档案确诊标签（DIABETES / HYPERTENSION / OBESITY）
 * @param tiers 疾病评估统一等级，键为大写病种编码
 * @param highlights 首诊问卷命中的高亮字段编码（如 basic.psych.eatingDisorderHistory）
 */
public record GuidelineFacts(
        Set<String> diseaseTags,
        boolean hypoglycemiaRisk,
        boolean fluidRestricted,
        Double bmi,
        Integer age,
        String sex,
        Map<String, AssessmentTier> tiers,
        boolean pregnantOrLactating,
        boolean eatingDisorderRisk,
        boolean exerciseRestricted,
        Set<String> highlights) {

    public static GuidelineFacts of(Set<String> diseaseTags) {
        return new GuidelineFacts(
                diseaseTags, false, false, null, null, null, Map.of(), false, false, false, Set.of());
    }

    /** 兼容旧构造（无高亮扩展字段）。 */
    public static GuidelineFacts basic(
            Set<String> diseaseTags,
            boolean hypoglycemiaRisk,
            boolean fluidRestricted,
            Double bmi,
            Integer age,
            String sex,
            Map<String, AssessmentTier> tiers) {
        return new GuidelineFacts(
                diseaseTags,
                hypoglycemiaRisk,
                fluidRestricted,
                bmi,
                age,
                sex,
                tiers == null ? Map.of() : tiers,
                false,
                false,
                false,
                Set.of());
    }
}
