package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.RuleSheet;
import java.util.List;
import java.util.Set;

/**
 * 评估引擎 SPI。每个病种一对引擎：未确诊走 {@link AssessmentCategory#RISK}，已确诊走 {@link AssessmentCategory#SEVERITY}。
 *
 * <p>等级名称、颜色由 {@link #levels()} 提供，编排器与前端不再按引擎写死。
 */
public interface AssessmentEngine {

    String code();

    AssessmentCategory category();

    AssessmentDisease disease();

    String label();

    GuidelineRef guideline();

    String rulePackVersion();

    /** 暂定口径（待医学团队校准） */
    default boolean provisional() {
        return false;
    }

    List<LevelDef> levels();

    Set<InputField> inputs();

    Set<InputField> requiredInputs();

    boolean applicable(AssessmentContext ctx);

    AssessmentResult evaluate(AssessmentContext ctx);

    RuleSheet ruleSheet();

    default LevelDef levelDef(String code) {
        if (code == null) {
            return null;
        }
        return levels().stream().filter(l -> l.code().equals(code)).findFirst().orElse(null);
    }

    default String diseaseCode() {
        return disease().code();
    }

    record GuidelineRef(String name, String version, Integer publishedYear) {}

    /** 引擎原生等级：编码、名称、统一等级（无数据类等级 tier 为 null）。 */
    record LevelDef(String code, String label, AssessmentTier tier) {}
}
