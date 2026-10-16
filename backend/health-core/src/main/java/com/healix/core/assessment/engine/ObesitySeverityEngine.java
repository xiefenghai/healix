package com.healix.core.assessment.engine;

import static com.healix.core.assessment.support.FactorCollector.SRC_ARCHIVE;
import static com.healix.core.assessment.support.FactorCollector.SRC_LAB;
import static com.healix.core.assessment.support.FactorCollector.SRC_METRIC;

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
import com.healix.core.assessment.support.FactorCollector;
import com.healix.core.assessment.support.Fmt;
import com.healix.core.assessment.support.HypertensionRiskRules;
import com.healix.core.assessment.support.HypertensionSeverityRules;
import com.healix.core.assessment.support.RuleSheet;
import com.healix.core.assessment.support.RuleSheet.Section;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 肥胖症严重等级分层：BMI 分度与并发症分期（参考 Edmonton 肥胖分期）取较高者。暂定口径。
 */
@Component
public class ObesitySeverityEngine implements AssessmentEngine {

    public static final String RULE_PACK = "OBESITY-SEV-2024.1-PROVISIONAL";
    static final String PROVISIONAL_NOTE = "暂定口径，待医学团队校准：BMI 分度与并发症分期取较高者";
    private static final GuidelineRef GUIDELINE = new GuidelineRef("肥胖症诊疗指南（2024年版）", "2024", 2024);
    private static final Set<InputField> REQUIRED = EnumSet.of(InputField.HEIGHT, InputField.WEIGHT);
    private static final Set<InputField> INPUTS = EnumSet.of(
            InputField.GENDER, InputField.HEIGHT, InputField.WEIGHT, InputField.WAIST, InputField.BLOOD_PRESSURE,
            InputField.GLUCOSE, InputField.HBA1C, InputField.LIPIDS, InputField.LIVER, InputField.URIC_ACID,
            InputField.PRESENT_ILLNESS, InputField.OSA, InputField.PCOS, InputField.PREDIABETES,
            InputField.DM_COMPLICATIONS);

    @Override
    public String code() {
        return AssessmentEngineCode.OBESITY_SEVERITY.name();
    }

    @Override
    public AssessmentCategory category() {
        return AssessmentCategory.SEVERITY;
    }

    @Override
    public AssessmentDisease disease() {
        return AssessmentDisease.OBESITY;
    }

    @Override
    public String label() {
        return "肥胖症分层";
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
    public boolean provisional() {
        return true;
    }

    @Override
    public List<LevelDef> levels() {
        return List.of(
                new LevelDef("LOW", "低", AssessmentTier.LOW),
                new LevelDef("MID", "中", AssessmentTier.MID),
                new LevelDef("HIGH", "高", AssessmentTier.HIGH),
                new LevelDef("VERY_HIGH", "很高", AssessmentTier.VERY_HIGH));
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
        if (!ctx.hasKnownObesity()) {
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
                    .notes(List.of(PROVISIONAL_NOTE))
                    .build();
        }
        BigDecimal bmi = ctx.getBmi();
        String bmiClass = bmiClass(bmi);
        AssessmentTier bmiTier = bmiTier(bmiClass);

        FactorCollector stage3 = new FactorCollector("STAGE_3", "3 期：终末器官损害");
        for (String[] it : new String[][] {{"CHD", "冠心病/心梗"}, {"HEART_FAILURE", "心力衰竭"}, {"STROKE", "脑卒中"}}) {
            if (Boolean.TRUE.equals(ctx.hasIllness(it[0]))) {
                stage3.hit(it[0], it[1], "现有疾病", SRC_ARCHIVE);
            }
        }
        if (Boolean.TRUE.equals(HypertensionSeverityRules.diabetesWithComplications(ctx))) {
            stage3.hit("DM_COMPLICATIONS", "糖尿病并发症",
                    String.join("、", ctx.factList(InputField.DM_COMPLICATIONS)), FactorCollector.SRC_QUESTIONNAIRE);
        }

        FactorCollector stage2 = new FactorCollector("STAGE_2", "2 期：已患相关慢病");
        if (ctx.hasKnownHypertension()) {
            stage2.hit("HYPERTENSION", "高血压", null, SRC_ARCHIVE);
        }
        if (ctx.hasKnownDiabetes()) {
            stage2.hit("DIABETES", "2 型糖尿病", null, SRC_ARCHIVE);
        }
        for (String[] it : new String[][] {
            {"HYPERLIPIDEMIA", "血脂异常"}, {"NAFLD", "脂肪肝"}, {"OSTEOARTHRITIS", "骨关节炎"}, {"CKD", "慢性肾病"}
        }) {
            if (Boolean.TRUE.equals(ctx.hasIllness(it[0]))) {
                stage2.hit(it[0], it[1], "现有疾病", SRC_ARCHIVE);
            }
        }
        if (!ctx.isPresentIllnessCollected()) {
            stage2.unchecked(InputField.PRESENT_ILLNESS, "相关慢病（现有疾病）");
        }
        stage2.fact(ctx, InputField.OSA, "OSA", "睡眠呼吸暂停");
        if (!ctx.isMale()) {
            stage2.fact(ctx, InputField.PCOS, "PCOS", "多囊卵巢综合征");
        }

        FactorCollector stage1 = new FactorCollector("STAGE_1", "1 期：亚临床异常");
        String grade = ctx.getSbp() == null ? null : HypertensionRiskRules.classifyBpGrade(ctx.getSbp(), ctx.getDbp());
        if (!ctx.hasKnownHypertension()) {
            if (grade == null) {
                stage1.unchecked(InputField.BLOOD_PRESSURE, "血压偏高");
            } else if (HypertensionRiskRules.gradeRank(grade) >= 0) {
                stage1.hit("BP_ELEVATED", "血压偏高", Fmt.bp(ctx), SRC_METRIC);
            }
        }
        if (!ctx.hasKnownDiabetes()) {
            stage1.check(HypertensionRiskRules.impairedGlucose(ctx), InputField.GLUCOSE, "IMPAIRED_GLUCOSE",
                    "空腹血糖受损/糖耐量异常", null, SRC_METRIC);
        }
        if (ctx.getAlt() == null) {
            stage1.unchecked(InputField.LIVER, "转氨酶升高");
        } else if (Fmt.gt(ctx.getAlt(), "40")) {
            stage1.hit("ALT_HIGH", "转氨酶升高", "ALT " + Fmt.num(ctx.getAlt()) + " U/L", SRC_LAB);
        }
        if (!stage2.has("HYPERLIPIDEMIA")) {
            if (ctx.getTc() == null && ctx.getTg() == null && ctx.getLdlC() == null && ctx.getHdlC() == null) {
                stage1.unchecked(InputField.LIPIDS, "血脂异常");
            } else if (Fmt.ge(ctx.getTg(), "1.7") || Fmt.ge(ctx.getTc(), "5.2") || Fmt.ge(ctx.getLdlC(), "3.4")
                    || Fmt.lt(ctx.getHdlC(), "1.0")) {
                stage1.hit("LIPID_ABNORMAL", "血脂异常", null, SRC_LAB);
            }
        }
        if (ctx.getUricAcid() != null && ctx.hasGender() && Fmt.gt(ctx.getUricAcid(), ctx.isMale() ? "420" : "360")) {
            stage1.hit("HYPERURICEMIA", "高尿酸血症", Fmt.num(ctx.getUricAcid()) + " μmol/L", SRC_LAB);
        }

        int stage = stage3.hitCount() > 0 ? 3 : stage2.hitCount() > 0 ? 2 : stage1.hitCount() > 0 ? 1 : 0;
        AssessmentTier stageTier = stage == 3 ? AssessmentTier.HIGH : stage == 2 ? AssessmentTier.MID : AssessmentTier.LOW;
        AssessmentTier tier = AssessmentTier.max(bmiTier, stageTier);
        String level = tier.name();

        String advice = switch (tier) {
            case VERY_HIGH -> "肥胖分层为极重度。建议安排专科医师评估后续干预方案，持续开展营养与运动管理";
            case HIGH -> "肥胖分层为重度或已有终末器官损害。建议安排专科医师评估，同时加强营养、运动与合并症随访";
            case MID -> "中度肥胖或已合并相关慢病，建议强化生活方式减重（目标 3–6 个月减重 5%–10%），关注合并症随访";
            default -> "轻度肥胖、无明显并发症，建议生活方式干预，目标减重 5%";
        };

        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(level)
                .tier(tier)
                .advice(advice)
                .dimensions(List.of(
                        new Dimension("BMI_CLASS", "BMI 分度", bmiClassLabel(bmiClass) + " · BMI " + Fmt.num(bmi),
                                bmiTier.name(), null),
                        new Dimension("STAGE", "并发症分期", stage + " 期", stageTier.name(), stageLabel(stage)),
                        new Dimension("CENTRAL", "中心性肥胖", ctx.centralObesity() == null ? "未测腰围"
                                : ctx.centralObesity() ? "是（腰围 " + Fmt.num(ctx.getWaistCm()) + " cm）" : "否", null, null)))
                .factorGroups(List.of(stage3.build(), stage2.build(), stage1.build()))
                .notes(List.of(PROVISIONAL_NOTE))
                .build();
    }

    static String bmiClass(BigDecimal bmi) {
        double v = bmi.doubleValue();
        if (v < 28.0) {
            return "BELOW";
        }
        if (v < 32.5) {
            return "MILD";
        }
        if (v < 37.5) {
            return "MODERATE";
        }
        if (v < 50.0) {
            return "SEVERE";
        }
        return "EXTREME";
    }

    static AssessmentTier bmiTier(String cls) {
        return switch (cls) {
            case "MODERATE" -> AssessmentTier.MID;
            case "SEVERE" -> AssessmentTier.HIGH;
            case "EXTREME" -> AssessmentTier.VERY_HIGH;
            default -> AssessmentTier.LOW;
        };
    }

    static String bmiClassLabel(String cls) {
        return switch (cls) {
            case "BELOW" -> "未达肥胖（<28）";
            case "MILD" -> "轻度（28.0–32.4）";
            case "MODERATE" -> "中度（32.5–37.4）";
            case "SEVERE" -> "重度（37.5–49.9）";
            default -> "极重度（≥50）";
        };
    }

    static String stageLabel(int stage) {
        return switch (stage) {
            case 3 -> "终末器官损害";
            case 2 -> "已患相关慢病";
            case 1 -> "亚临床异常";
            default -> "无相关异常";
        };
    }

    @Override
    public RuleSheet ruleSheet() {
        return new RuleSheet(
                "适用已确诊肥胖者。BMI 分度与并发症分期分别定级，取较高者。",
                List.of(
                        Section.table("BMI 分度", List.of("分度", "BMI kg/m²", "等级"), List.of(
                                List.of("轻度", "28.0–32.4", "低"), List.of("中度", "32.5–37.4", "中"),
                                List.of("重度", "37.5–49.9", "高"), List.of("极重度", "≥50", "很高"))),
                        Section.table("并发症分期", List.of("分期", "条件", "等级"), List.of(
                                List.of("0 期", "无相关异常", "低"),
                                List.of("1 期", "亚临床异常：血压偏高、空腹血糖受损、转氨酶升高、血脂异常、高尿酸", "低"),
                                List.of("2 期", "已患相关慢病：高血压、2 型糖尿病、睡眠呼吸暂停、骨关节炎、脂肪肝、多囊卵巢、血脂异常、慢性肾病", "中"),
                                List.of("3 期", "终末器官损害：心梗/冠心病、心衰、脑卒中、糖尿病并发症", "高")))),
                List.of(PROVISIONAL_NOTE, "睡眠呼吸暂停、多囊卵巢、糖尿病并发症可在评估问卷补充"));
    }
}
