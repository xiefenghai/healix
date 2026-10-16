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
import com.healix.core.assessment.support.HypertensionRiskRules;
import com.healix.core.assessment.support.RuleSheet;
import com.healix.core.assessment.support.RuleSheet.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 高血压患病风险，《中国高血压健康管理规范（2019）》。
 *
 * <p>高：血压达 1 级及以上（疑似）或心脑血管病风险高危组合；中：高血压前期或命中易患因素；低：其余。
 */
@Component
public class HypertensionAssessmentEngine implements AssessmentEngine {

    public static final String RULE_PACK = "HTN-RISK-2019.2";
    private static final GuidelineRef GUIDELINE = new GuidelineRef("中国高血压健康管理规范", "2019", 2019);
    private static final Set<InputField> REQUIRED = EnumSet.of(InputField.BLOOD_PRESSURE);
    private static final Set<InputField> INPUTS = EnumSet.of(
            InputField.BLOOD_PRESSURE, InputField.GENDER, InputField.BIRTHDAY, InputField.HEIGHT, InputField.WEIGHT,
            InputField.WAIST, InputField.FAMILY_HISTORY, InputField.HIGH_SALT, InputField.DRINKING, InputField.SMOKING,
            InputField.PASSIVE_SMOKING, InputField.EXERCISE, InputField.CHRONIC_STRESS, InputField.GLUCOSE,
            InputField.PREDIABETES, InputField.LIPIDS, InputField.HEART_RATE, InputField.URIC_ACID,
            InputField.EARLY_MENOPAUSE, InputField.ANTIHYPERTENSIVE);

    @Override
    public String code() {
        return AssessmentEngineCode.HYPERTENSION_RISK.name();
    }

    @Override
    public AssessmentCategory category() {
        return AssessmentCategory.RISK;
    }

    @Override
    public AssessmentDisease disease() {
        return AssessmentDisease.HYPERTENSION;
    }

    @Override
    public String label() {
        return "高血压患病风险";
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
                new LevelDef("LOW", "低风险", AssessmentTier.LOW),
                new LevelDef("MID", "中风险", AssessmentTier.MID),
                new LevelDef("HIGH", "高风险", AssessmentTier.HIGH));
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
        return !ctx.hasKnownHypertension();
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        List<Missing> missing = FactorCollector.missing(ctx, REQUIRED);
        Suspicion therapySuspicion = Boolean.TRUE.equals(ctx.antihypertensiveTherapy())
                ? new Suspicion(AssessmentDisease.HYPERTENSION.code(), AssessmentDisease.HYPERTENSION.label(),
                        "正在服用降压药，疑似已患高血压，建议确诊并建立高血压档案")
                : null;
        if (!missing.isEmpty()) {
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .advice("缺少血压记录，补录后自动重新评估")
                    .missing(missing)
                    .suspicion(therapySuspicion)
                    .build();
        }

        String grade = HypertensionRiskRules.classifyBpGrade(ctx.getSbp(), ctx.getDbp());
        boolean isolated = HypertensionRiskRules.isIsolatedSystolic(ctx.getSbp(), ctx.getDbp());
        FactorCollector susceptible = HypertensionRiskRules.susceptible(ctx, grade);
        FactorCollector major = HypertensionRiskRules.majorRiskFactors(ctx);
        boolean cvHigh = HypertensionRiskRules.isCvHighRisk(grade, major.hitCount());
        int rank = HypertensionRiskRules.gradeRank(grade);

        String level;
        String advice;
        Suspicion suspicion = therapySuspicion;
        if (rank >= 1) {
            level = "HIGH";
            suspicion = new Suspicion(AssessmentDisease.HYPERTENSION.code(), AssessmentDisease.HYPERTENSION.label(),
                    "血压 " + Fmt.bp(ctx) + " 达" + HypertensionRiskRules.bpGradeLabel(grade) + "，疑似高血压，建议确诊并建立高血压档案");
            advice = suspicion.reason() + (cvHigh ? "；合并危险因素，心脑血管病风险高危，建议尽快安排医师就诊" : "");
        } else if (cvHigh) {
            level = "HIGH";
            advice = "高血压前期且合并 " + major.hitCount() + " 个主要危险因素，心脑血管病风险高危；建议强化生活方式干预，3–6 个月复查血压";
        } else if (rank == 0 || susceptible.hitCount() > 0) {
            level = "MID";
            advice = "属高血压易患人群（命中 " + susceptible.hitCount() + " 项），建议生活方式干预，每 3–6 个月测量血压";
        } else {
            level = "LOW";
            advice = "血压正常且未命中易患因素，建议每年测量血压 1–2 次";
        }
        if (suspicion != null && rank < 1) {
            advice = suspicion.reason() + "；" + advice;
        }

        List<String> notes = new ArrayList<>();
        notes.add("血压取值：" + Fmt.bpSource(ctx));
        if (Boolean.TRUE.equals(ctx.antihypertensiveTherapy())) {
            notes.add("正在服用降压药，测量值可能低估真实血压水平");
        }

        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(level)
                .tier(levelDef(level).tier())
                .advice(advice)
                .dimensions(List.of(
                        new Dimension("BP_GRADE", "血压分级",
                                HypertensionRiskRules.bpGradeLabel(grade) + (isolated ? "（单纯收缩期）" : "") + " · " + Fmt.bp(ctx),
                                rank >= 1 ? "HIGH" : rank == 0 ? "MID" : "LOW", Fmt.bpSource(ctx)),
                        new Dimension("SUSCEPTIBLE", "易患人群", susceptible.hitCount() > 0 ? "是（" + susceptible.hitCount() + " 项）" : "否",
                                susceptible.hitCount() > 0 ? "MID" : "LOW", null),
                        new Dimension("CV_RISK", "心脑血管病风险", cvHigh ? "高危" : "非高危",
                                cvHigh ? "HIGH" : "LOW", "主要危险因素 " + major.hitCount() + " 个")))
                .factorGroups(List.of(susceptible.build(), major.build()))
                .notes(notes)
                .suspicion(suspicion)
                .build();
    }

    @Override
    public RuleSheet ruleSheet() {
        return new RuleSheet(
                "适用未确诊高血压者。高风险：血压达 1 级及以上（疑似高血压），或心脑血管病风险高危组合；中风险：高血压前期或命中任一易患因素；低风险：其余。",
                List.of(
                        Section.table("血压分级（收缩压/舒张压取较高一级）", List.of("分级", "收缩压 mmHg", "舒张压 mmHg"), List.of(
                                List.of("正常", "<120", "<80"), List.of("高血压前期", "120–139", "80–89"),
                                List.of("1级", "140–159", "90–99"), List.of("2级", "160–179", "100–109"),
                                List.of("3级", "≥180", "≥110"))),
                        Section.bullets("易患因素", List.of(
                                "高血压前期", "年龄≥45岁", "超重/肥胖（BMI≥24）或中心性肥胖", "高血压家族史", "高盐饮食",
                                "长期大量饮酒", "吸烟（含被动吸烟）", "缺乏体力活动", "长期精神紧张")),
                        Section.bullets("心脑血管病主要危险因素", List.of(
                                "年龄（男>55 / 女>65）", "吸烟", "糖耐量受损或空腹血糖异常",
                                "血脂异常（TC≥5.7 或 LDL-C>3.3 或 HDL-C<1.0 mmol/L）", "早发心血管病家族史",
                                "腹型肥胖或 BMI≥28", "静坐生活方式", "静息心率>80次/分", "高尿酸血症（男>420 / 女>360 μmol/L）",
                                "早发绝经（<50岁）")),
                        Section.table("心脑血管病风险高危组合", List.of("血压分级", "主要危险因素"), List.of(
                                List.of("3级", "不论"), List.of("2级", "≥1 个"), List.of("前期或 1 级", "≥3 个")))),
                List.of("血压取近 4 周均值（至少 2 次）；不足时取最新一次", "正在服降压药提示疑似已患病，建议确诊建档"));
    }
}
