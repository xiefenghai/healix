package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentKind;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;

/** 评估规则引擎 SPI。 */
public interface AssessmentEngine {

    String code();

    AssessmentKind kind();

    String diseaseCode();

    GuidelineRef guideline();

    String rulePackVersion();

    boolean applicable(AssessmentContext ctx);

    AssessmentResult evaluate(AssessmentContext ctx);

    record GuidelineRef(String name, String version, Integer publishedYear) {}
}
