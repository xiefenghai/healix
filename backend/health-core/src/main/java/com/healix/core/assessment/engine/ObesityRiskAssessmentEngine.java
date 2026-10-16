package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.Dimension;
import com.healix.core.assessment.support.AssessmentResult.Missing;
import com.healix.core.assessment.support.AssessmentResult.Suspicion;
import com.healix.core.assessment.support.FactorCollector;
import com.healix.core.assessment.support.Fmt;
import com.healix.core.assessment.support.RuleSheet;
import com.healix.core.assessment.support.RuleSheet.Section;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 肥胖筛查（患病风险），《肥胖症诊疗指南（2024年版）》。
 *
 * <p>BMI 正常 → 低；超重或中心性肥胖 → 中；BMI ≥28 → 高（疑似肥胖症，建议确诊）。
 */
@Component
public class ObesityRiskAssessmentEngine implements AssessmentEngine {

    public static final String RULE_PACK = "OBESITY-RISK-2024.3";
    private static final GuidelineRef GUIDELINE = new GuidelineRef("肥胖症诊疗指南", "2024", 2024);
    private static final Set<InputField> REQUIRED = EnumSet.of(InputField.HEIGHT, InputField.WEIGHT);
    private static final Set<InputField> INPUTS =
            EnumSet.of(InputField.GENDER, InputField.HEIGHT, InputField.WEIGHT, InputField.WAIST, InputField.BODY_FAT);

    public static final String UNDERWEIGHT = "UNDERWEIGHT";
    public static final String NORMAL = "NORMAL";
    public static final String NORMAL_CENTRAL = "NORMAL_CENTRAL";
    public static final String OVERWEIGHT = "OVERWEIGHT";
    public static final String OBESITY = "OBESITY";

    @Override
    public String code() {
        return AssessmentEngineCode.OBESITY_RISK.name();
    }

    @Override
    public AssessmentCategory category() {
        return AssessmentCategory.RISK;
    }

    @Override
    public AssessmentDisease disease() {
        return AssessmentDisease.OBESITY;
    }

    @Override
    public String label() {
        return "肥胖筛查";
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
    public List<LevelDef> levels() {
        return List.of(
                new LevelDef(UNDERWEIGHT, "偏瘦", AssessmentTier.LOW),
                new LevelDef(NORMAL, "正常", AssessmentTier.LOW),
                new LevelDef(NORMAL_CENTRAL, "中心性肥胖", AssessmentTier.MID),
                new LevelDef(OVERWEIGHT, "超重", AssessmentTier.MID),
                new LevelDef(OBESITY, "肥胖（疑似肥胖症）", AssessmentTier.HIGH));
    }

    @Override
    public Set<InputField> inputs() {
        return INPUTS;
    }

    @Override
    public Set<InputField> requiredInputs() {
        return REQUIRED;
    }

    @Override
    public boolean applicable(AssessmentContext ctx) {
        if (ctx.hasKnownObesity()) {
            return false;
        }
        Integer age = ctx.getAgeYears();
        return age == null || age >= 18;
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        List<Missing> missing = FactorCollector.missing(ctx, REQUIRED);
        if (!missing.isEmpty()) {
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .advice("缺少身高体重，补齐后自动重新评估")
                    .missing(missing)
                    .build();
        }
        BigDecimal bmi = ctx.getBmi();
        String diagnosis = diagnoseByBmi(bmi);
        Boolean central = ctx.centralObesity();
        BigDecimal bodyFat = ctx.factNumber(InputField.BODY_FAT);
        Boolean highFat = bodyFat == null || !ctx.hasGender() ? null : Fmt.gt(bodyFat, ctx.isMale() ? "25" : "30");

        String level = diagnosis;
        if (NORMAL.equals(diagnosis) && Boolean.TRUE.equals(central)) {
            level = NORMAL_CENTRAL;
        }
        Suspicion suspicion = null;
        String advice;
        switch (level) {
            case OBESITY -> {
                suspicion = new Suspicion(AssessmentDisease.OBESITY.code(), AssessmentDisease.OBESITY.label(),
                        "BMI " + Fmt.num(bmi) + "（≥28），疑似肥胖症，建议确诊并建立肥胖档案");
                advice = suspicion.reason() + (Boolean.TRUE.equals(central) ? "；同时存在中心性肥胖，代谢风险更高" : "");
            }
            case OVERWEIGHT -> advice = Boolean.TRUE.equals(central)
                    ? "超重且合并中心性肥胖，建议强化生活方式干预，定期复测体重与腰围"
                    : "BMI 属于超重（24.0–27.9），建议生活方式干预，防止进展为肥胖症";
            case NORMAL_CENTRAL -> advice = "BMI 正常但存在中心性肥胖，建议控制腰围并关注代谢指标";
            case UNDERWEIGHT -> advice = "BMI 偏低，建议关注营养与体重管理";
            default -> advice = "体重处于理想范围，建议保持健康生活方式";
        }

        List<Dimension> dims = new ArrayList<>();
        dims.add(new Dimension("BMI", "BMI", Fmt.num(bmi) + " · " + diagnosisLabel(diagnosis),
                OBESITY.equals(diagnosis) ? "HIGH" : OVERWEIGHT.equals(diagnosis) ? "MID" : "LOW", "中国成人 BMI≥28 为肥胖"));
        dims.add(new Dimension("CENTRAL", "中心性肥胖",
                central == null ? "未测腰围" : central ? "是（腰围 " + Fmt.num(ctx.getWaistCm()) + " cm）" : "否（腰围 " + Fmt.num(ctx.getWaistCm()) + " cm）",
                Boolean.TRUE.equals(central) ? "MID" : central == null ? null : "LOW", "男≥90 / 女≥85 cm"));
        if (bodyFat != null) {
            dims.add(new Dimension("BODY_FAT", "体脂率", Fmt.num(bodyFat) + "%" + (Boolean.TRUE.equals(highFat) ? "（偏高）" : ""),
                    Boolean.TRUE.equals(highFat) ? "MID" : "LOW", "男>25% / 女>30% 偏高，仅作辅助参考"));
        }

        FactorCollector extra = new FactorCollector("OBESITY_AUX", "辅助指标");
        if (central == null) {
            extra.unchecked(ctx.getWaistCm() == null ? InputField.WAIST : InputField.GENDER, "中心性肥胖（腰围）");
        }
        if (bodyFat == null) {
            extra.unchecked(InputField.BODY_FAT, "体脂率");
        }
        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(level)
                .tier(levelDef(level).tier())
                .advice(advice)
                .dimensions(dims)
                .factorGroups(extra.build().unchecked().isEmpty() ? List.of() : List.of(extra.build()))
                .suspicion(suspicion)
                .build();
    }

    public static String diagnoseByBmi(BigDecimal bmi) {
        double v = bmi.doubleValue();
        if (v < 18.5) {
            return UNDERWEIGHT;
        }
        if (v < 24.0) {
            return NORMAL;
        }
        if (v < 28.0) {
            return OVERWEIGHT;
        }
        return OBESITY;
    }

    static String diagnosisLabel(String d) {
        return switch (d) {
            case UNDERWEIGHT -> "偏瘦";
            case NORMAL -> "正常";
            case OVERWEIGHT -> "超重";
            case OBESITY -> "肥胖";
            default -> d;
        };
    }

    @Override
    public RuleSheet ruleSheet() {
        return new RuleSheet(
                "适用 18 岁及以上未确诊肥胖者。BMI≥28 → 高（疑似肥胖症，建议确诊）；超重或中心性肥胖 → 中；其余 → 低。",
                List.of(
                        Section.table("BMI 判定（中国成人）", List.of("BMI kg/m²", "判定", "等级"), List.of(
                                List.of("<18.5", "偏瘦", "低"), List.of("18.5–23.9", "正常", "低（伴中心性肥胖为中）"),
                                List.of("24.0–27.9", "超重", "中"), List.of("≥28.0", "肥胖", "高"))),
                        Section.table("中心性肥胖（腰围）", List.of("性别", "腰围 cm"), List.of(
                                List.of("男", "≥90"), List.of("女", "≥85"))),
                        Section.bullets("辅助", List.of("体脂率 男>25% / 女>30% 偏高，仅展示，不改变等级"))),
                List.of("儿童青少年需按年龄别百分位评估，本引擎不适用"));
    }
}
