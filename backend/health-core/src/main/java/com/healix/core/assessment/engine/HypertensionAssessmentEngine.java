package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentKind;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.AssessmentItem;
import com.healix.core.assessment.support.HypertensionRiskRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 高血压风险评估（《中国高血压健康管理规范（2019）》）。
 *
 * <p>输出三个并列结果：血压水平分级、高血压易患人群、心脑血管病风险。
 */
@Component
public class HypertensionAssessmentEngine implements AssessmentEngine {

    public static final String RULE_PACK = "HTN-RISK-2019.1";
    private static final GuidelineRef GUIDELINE =
            new GuidelineRef("中国高血压健康管理规范", "2019", 2019);

    @Override
    public String code() {
        return AssessmentEngineCode.HYPERTENSION_RISK.name();
    }

    @Override
    public AssessmentKind kind() {
        return AssessmentKind.INCIDENT_RISK;
    }

    @Override
    public String diseaseCode() {
        return "hypertension";
    }

    @Override
    public GuidelineRef guideline() {
        return GUIDELINE;
    }

    @Override
    public String rulePackVersion() {
        return RULE_PACK;
    }

    @Override
    public boolean applicable(AssessmentContext ctx) {
        return !ctx.hasKnownHypertension();
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        List<String> missing = new ArrayList<>();
        if (ctx.getSbp() == null) {
            missing.add("BLOOD_PRESSURE_SYS");
        }
        if (ctx.getDbp() == null) {
            missing.add("BLOOD_PRESSURE_DIA");
        }
        if (!missing.isEmpty()) {
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .missingFields(missing)
                    .advice("请补齐收缩压与舒张压后再评估")
                    .guideline(GUIDELINE)
                    .rulePackVersion(RULE_PACK)
                    .extras(baseExtras(ctx))
                    .build();
        }

        String bpGrade = HypertensionRiskRules.classifyBpGrade(ctx.getSbp(), ctx.getDbp());
        boolean isolated = HypertensionRiskRules.isIsolatedSystolic(ctx.getSbp(), ctx.getDbp());
        Map<String, Object> susceptible = HypertensionRiskRules.evaluateSusceptible(ctx, bpGrade);
        Map<String, Object> major = HypertensionRiskRules.evaluateMajorRiskFactors(ctx);
        int factorCount = ((Number) major.get("hitCount")).intValue();
        boolean cvHigh = HypertensionRiskRules.isCvHighRisk(bpGrade, factorCount);
        boolean isSusceptible = Boolean.TRUE.equals(susceptible.get("susceptible"));

        String advice = HypertensionRiskRules.adviceFor(bpGrade, isSusceptible, cvHigh, factorCount);

        List<AssessmentItem> items = new ArrayList<>();
        items.add(item(
                "SBP",
                "收缩压",
                ctx.getSbp().toPlainString() + " mmHg",
                null,
                "参与血压水平分级"));
        items.add(item(
                "DBP",
                "舒张压",
                ctx.getDbp().toPlainString() + " mmHg",
                null,
                "参与血压水平分级"));
        items.add(item(
                "BP_GRADE",
                "血压水平分级",
                HypertensionRiskRules.bpGradeLabel(bpGrade)
                        + (isolated ? "（单纯收缩期高血压）" : ""),
                null,
                "SBP/DBP 取较高一级"));
        items.add(item(
                "SUSCEPTIBLE",
                "高血压易患人群",
                isSusceptible ? "是" : "否",
                null,
                String.valueOf(susceptible.get("advice"))));
        items.add(item(
                "MAJOR_RF",
                "主要危险因素数",
                String.valueOf(factorCount),
                factorCount,
                "用于心脑血管病风险高危判定"));
        items.add(item(
                "CV_RISK",
                "心脑血管病风险",
                cvHigh ? "高危" : "非高危",
                null,
                cvHigh
                        ? "符合分级+危险因素组合的高危条件"
                        : "未达高危组合条件"));

        Map<String, Object> extras = baseExtras(ctx);
        extras.put("bpGrade", bpGrade);
        extras.put("bpGradeLabel", HypertensionRiskRules.bpGradeLabel(bpGrade));
        extras.put("isolatedSystolic", isolated);
        extras.put("susceptible", isSusceptible);
        extras.put("susceptibleDetail", susceptible);
        extras.put("cvRiskHigh", cvHigh);
        extras.put("cvRiskLabel", cvHigh ? "高危" : "非高危");
        extras.put("majorRiskFactors", major);
        extras.put("majorRiskFactorCount", factorCount);
        extras.put(
                "dimensions",
                List.of(
                        Map.of(
                                "code", "BP_GRADE",
                                "label", "血压水平分级",
                                "value", HypertensionRiskRules.bpGradeLabel(bpGrade)
                                        + (isolated ? "（单纯收缩期）" : ""),
                                "hint", "描述血压现状"),
                        Map.of(
                                "code", "SUSCEPTIBLE",
                                "label", "高血压易患人群",
                                "value", isSusceptible ? "是" : "否",
                                "hint", "是否需要重点预防"),
                        Map.of(
                                "code", "CV_RISK",
                                "label", "心脑血管病风险",
                                "value", cvHigh ? "高危" : "非高危",
                                "hint", "未来心梗/脑卒中风险（非诊断）")));

        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(bpGrade)
                .score(ctx.getSbp())
                .items(items)
                .advice(advice)
                .guideline(GUIDELINE)
                .rulePackVersion(RULE_PACK)
                .extras(extras)
                .build();
    }

    private static Map<String, Object> baseExtras(AssessmentContext ctx) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("guidelineRef", "《中国高血压健康管理规范（2019）》");
        extras.put(
                "designNote",
                "血压水平分级与心脑血管病风险为并列独立结果，不可混为一谈");
        extras.put("hasHypertensionDiseaseArchive", ctx.isHasHypertensionDiseaseArchive());
        return extras;
    }

    private static AssessmentItem item(
            String code, String label, String input, Integer points, String rationale) {
        return AssessmentItem.builder()
                .code(code)
                .label(label)
                .inputValue(input)
                .points(points)
                .rationale(rationale)
                .build();
    }
}
