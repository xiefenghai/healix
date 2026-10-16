package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.Comparison;
import com.healix.core.assessment.support.AssessmentResult.Dimension;
import com.healix.core.assessment.support.FactorCollector;
import com.healix.core.assessment.support.Fmt;
import com.healix.core.assessment.support.HypertensionSeverityRules;
import com.healix.core.assessment.support.RuleSheet;
import com.healix.core.assessment.support.RuleSheet.Section;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 高血压严重等级分层（心血管风险分层），《中国高血压防治指南（2018 年修订版）》。暂定口径。
 */
@Component
public class HypertensionSeverityEngine implements AssessmentEngine {

    public static final String RULE_PACK = "HTN-SEV-2018.1-PROVISIONAL";
    static final String PROVISIONAL_NOTE = "暂定口径，待医学团队校准：「中/高危」「高/很高危」两格暂取较高档";
    private static final GuidelineRef GUIDELINE = new GuidelineRef("中国高血压防治指南（2018年修订版）", "2018", 2018);
    private static final Set<InputField> INPUTS = EnumSet.of(
            InputField.HTN_MAX_SBP, InputField.HTN_MAX_DBP, InputField.BLOOD_PRESSURE, InputField.GENDER,
            InputField.BIRTHDAY, InputField.SMOKING, InputField.PASSIVE_SMOKING, InputField.GLUCOSE,
            InputField.PREDIABETES, InputField.LIPIDS, InputField.FAMILY_HISTORY, InputField.HEIGHT, InputField.WEIGHT,
            InputField.WAIST, InputField.HCY_HIGH, InputField.RENAL, InputField.LVH, InputField.CAROTID_PLAQUE,
            InputField.PWV_HIGH, InputField.ABI_LOW, InputField.PRESENT_ILLNESS, InputField.PERIPHERAL_ARTERY_DISEASE,
            InputField.ADVANCED_RETINOPATHY, InputField.DM_COMPLICATIONS);

    @Override
    public String code() {
        return AssessmentEngineCode.HYPERTENSION_SEVERITY.name();
    }

    @Override
    public AssessmentCategory category() {
        return AssessmentCategory.SEVERITY;
    }

    @Override
    public AssessmentDisease disease() {
        return AssessmentDisease.HYPERTENSION;
    }

    @Override
    public String label() {
        return "高血压心血管风险分层";
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
                new LevelDef(HypertensionSeverityRules.LOW, "低危", AssessmentTier.LOW),
                new LevelDef(HypertensionSeverityRules.MODERATE, "中危", AssessmentTier.MID),
                new LevelDef(HypertensionSeverityRules.HIGH, "高危", AssessmentTier.HIGH),
                new LevelDef(HypertensionSeverityRules.VERY_HIGH, "很高危", AssessmentTier.VERY_HIGH));
    }

    @Override
    public Set<InputField> inputs() {
        return INPUTS;
    }

    /** 既往最高血压与近期血压二选一，在 evaluate 中判断。 */
    @Override
    public Set<InputField> requiredInputs() {
        return Set.of();
    }

    @Override
    public boolean applicable(AssessmentContext ctx) {
        return ctx.hasKnownHypertension();
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        BigDecimal sbp;
        BigDecimal dbp;
        String bpSource;
        if (ctx.highestSbp() != null || ctx.highestDbp() != null) {
            sbp = ctx.highestSbp();
            dbp = ctx.highestDbp();
            bpSource = ctx.getArchiveHighestSbp() != null || ctx.getArchiveHighestDbp() != null
                    ? "既往最高血压（病种档案）"
                    : "既往最高血压（问卷）";
        } else if (ctx.getSbp() != null) {
            sbp = ctx.getSbp();
            dbp = ctx.getDbp();
            bpSource = Fmt.bpSource(ctx);
        } else {
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .advice("缺少血压：请补充既往最高血压（问卷）或记录近期血压")
                    .missing(List.of(
                            FactorCollector.missingOf(InputField.HTN_MAX_SBP),
                            FactorCollector.missingOf(InputField.BLOOD_PRESSURE)))
                    .notes(List.of(PROVISIONAL_NOTE))
                    .build();
        }

        List<String> notes = new ArrayList<>();
        notes.add(PROVISIONAL_NOTE);
        int column = HypertensionSeverityRules.bpColumn(sbp, dbp);
        if (column < 0) {
            column = 0;
            notes.add("血压低于正常高值（可能为服药后血压），按正常高值列分层；建议补充未服药时的既往最高血压");
        }

        FactorCollector rf = HypertensionSeverityRules.riskFactors(ctx);
        FactorCollector tod = HypertensionSeverityRules.targetOrganDamage(ctx);
        FactorCollector acc = HypertensionSeverityRules.clinicalComplications(ctx);
        boolean dmNoComplication = ctx.hasKnownDiabetes() && !Boolean.TRUE.equals(HypertensionSeverityRules.diabetesWithComplications(ctx));
        int row;
        String rowReason;
        if (acc.hitCount() > 0) {
            row = 3;
            rowReason = "存在临床并发症";
        } else if (rf.hitCount() >= 3 || tod.hitCount() > 0 || dmNoComplication) {
            row = 2;
            rowReason = tod.hitCount() > 0 ? "存在靶器官损害" : dmNoComplication ? "合并糖尿病（无并发症）" : "危险因素 ≥3 个";
        } else if (rf.hitCount() > 0) {
            row = 1;
            rowReason = "危险因素 " + rf.hitCount() + " 个";
        } else {
            row = 0;
            rowReason = "无其他危险因素";
        }
        String level = HypertensionSeverityRules.stratify(row, column);
        String levelLabel = HypertensionSeverityRules.levelLabel(level);

        List<Comparison> comparisons = new ArrayList<>();
        if (ctx.getArchiveCvRisk() != null && !ctx.getArchiveCvRisk().isBlank()) {
            String archived = ctx.getArchiveCvRisk().trim().toUpperCase(Locale.ROOT);
            boolean same = archived.equals(level);
            comparisons.add(new Comparison("档案手填分层", HypertensionSeverityRules.levelLabel(archived), levelLabel, same,
                    same ? null : "与计算结果不一致，请核对后在病种档案更新（不自动覆盖）"));
        }

        String advice = switch (level) {
            case HypertensionSeverityRules.VERY_HIGH -> "心血管风险分层为很高危。建议优先安排医师复诊，由医师评估降压方案；加强血压监测，协助管理并存的危险因素";
            case HypertensionSeverityRules.HIGH -> "心血管风险分层为高危。建议安排医师复诊评估降压方案，同时加强血压监测与危险因素管理";
            case HypertensionSeverityRules.MODERATE -> "心血管风险分层为中危。建议生活方式干预并加强血压随访，按医师意见复诊";
            default -> "心血管风险分层为低危。建议生活方式干预，定期复测血压";
        };

        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(level)
                .tier(levelDef(level).tier())
                .advice(advice)
                .dimensions(List.of(
                        new Dimension("BP_GRADE", "血压分级", HypertensionSeverityRules.COLUMNS.get(column)
                                + " · " + Fmt.num(sbp) + "/" + Fmt.num(dbp) + " mmHg", null, bpSource),
                        new Dimension("ROW", "危险因素与病史", HypertensionSeverityRules.ROWS.get(row), null, rowReason),
                        new Dimension("STRATUM", "心血管风险分层", levelLabel, levelDef(level).tier().name(),
                                HypertensionSeverityRules.MATRIX_LABELS[row][column])))
                .factorGroups(List.of(rf.build(), tod.build(), acc.build()))
                .comparisons(comparisons)
                .notes(notes)
                .build();
    }

    @Override
    public RuleSheet ruleSheet() {
        List<String> headers = new ArrayList<>();
        headers.add("其他危险因素与病史");
        headers.addAll(HypertensionSeverityRules.COLUMNS);
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            List<String> row = new ArrayList<>();
            row.add(HypertensionSeverityRules.ROWS.get(r));
            row.addAll(List.of(HypertensionSeverityRules.MATRIX_LABELS[r]));
            rows.add(row);
        }
        return new RuleSheet(
                "适用已确诊高血压者。按血压分级与危险因素/靶器官损害/临床并发症查表得出低危、中危、高危、很高危。",
                List.of(
                        Section.table("心血管风险分层表", headers, rows),
                        Section.bullets("危险因素", List.of(
                                "男>55 岁 / 女>65 岁", "吸烟或被动吸烟", "糖耐量受损或空腹血糖异常",
                                "血脂异常（TC≥5.2 或 LDL-C≥3.4 或 HDL-C<1.0 mmol/L）", "早发心血管病家族史",
                                "腹型肥胖（男腰围≥90 / 女≥85 cm）或 BMI≥28", "高同型半胱氨酸（≥15 μmol/L）")),
                        Section.bullets("靶器官损害", List.of(
                                "左心室肥厚", "颈动脉内中膜厚度≥0.9 mm 或斑块", "颈-股动脉脉搏波速度≥12 m/s", "踝臂指数<0.9",
                                "eGFR 30–59 或血肌酐轻度升高（男 115–133 / 女 107–124 μmol/L）", "尿白蛋白/肌酐比 30–300 mg/g")),
                        Section.bullets("临床并发症", List.of(
                                "脑血管病", "心脏疾病（冠心病、心衰、房颤）", "慢性肾病（eGFR<30 或尿白蛋白/肌酐比>300 mg/g）",
                                "外周血管疾病", "视网膜病变（出血/渗出/视乳头水肿）", "有并发症的糖尿病"))),
                List.of(PROVISIONAL_NOTE,
                        "血压优先取病种档案「既往最高血压」（指南按未治疗时血压分层），没有时取近 4 周均值",
                        "计算结果与档案手填分层不一致时仅提示，不自动覆盖"));
    }
}
