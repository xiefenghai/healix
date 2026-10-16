package com.healix.core.assessment.support;

import static com.healix.core.assessment.support.FactorCollector.SRC_ARCHIVE;
import static com.healix.core.assessment.support.FactorCollector.SRC_LAB;
import static com.healix.core.assessment.support.FactorCollector.SRC_METRIC;

import com.healix.core.assessment.catalog.InputField;
import java.math.BigDecimal;
import java.util.List;

/**
 * 高血压患者心血管风险分层，《中国高血压防治指南（2018 年修订版）》。
 *
 * <p>「中/高危」「高/很高危」两格暂取较高档（暂定口径，待医学团队校准）。
 */
public final class HypertensionSeverityRules {

    private HypertensionSeverityRules() {}

    public static final String LOW = "LOW";
    public static final String MODERATE = "MODERATE";
    public static final String HIGH = "HIGH";
    public static final String VERY_HIGH = "VERY_HIGH";

    /** 列：0 正常高值，1 一级，2 二级，3 三级。 */
    public static final List<String> COLUMNS = List.of("正常高值 130–139/85–89", "1级", "2级", "3级");

    /** 行：0 无其他危险因素，1 1–2 个，2 ≥3 个/靶器官损害/CKD3/无并发症糖尿病，3 临床并发症/CKD≥4/有并发症糖尿病。 */
    public static final List<String> ROWS = List.of(
            "无其他危险因素",
            "1–2 个危险因素",
            "≥3 个危险因素，或靶器官损害，或慢性肾病 3 期，或无并发症的糖尿病",
            "临床并发症，或慢性肾病 ≥4 期，或有并发症的糖尿病");

    private static final String[][] MATRIX = {
        {LOW, LOW, MODERATE, HIGH},
        {LOW, MODERATE, HIGH, VERY_HIGH},
        {HIGH, HIGH, HIGH, VERY_HIGH},
        {VERY_HIGH, VERY_HIGH, VERY_HIGH, VERY_HIGH},
    };

    /** 指南原表文字，用于规则说明。 */
    public static final String[][] MATRIX_LABELS = {
        {"—（按低危）", "低危", "中危", "高危"},
        {"低危", "中危", "中/高危 → 高危", "很高危"},
        {"中/高危 → 高危", "高危", "高危", "很高危"},
        {"高/很高危 → 很高危", "很高危", "很高危", "很高危"},
    };

    public static String stratify(int row, int column) {
        return MATRIX[row][column];
    }

    public static String levelLabel(String level) {
        if (level == null) {
            return null;
        }
        return switch (level) {
            case LOW -> "低危";
            case MODERATE -> "中危";
            case HIGH -> "高危";
            case VERY_HIGH -> "很高危";
            default -> level;
        };
    }

    /** 2018 指南分级；低于正常高值返回 -1。 */
    public static int bpColumn(BigDecimal sbp, BigDecimal dbp) {
        int s = sbp == null ? -1 : sbp.doubleValue() >= 180 ? 3 : sbp.doubleValue() >= 160 ? 2 : sbp.doubleValue() >= 140 ? 1 : sbp.doubleValue() >= 130 ? 0 : -1;
        int d = dbp == null ? -1 : dbp.doubleValue() >= 110 ? 3 : dbp.doubleValue() >= 100 ? 2 : dbp.doubleValue() >= 90 ? 1 : dbp.doubleValue() >= 85 ? 0 : -1;
        return Math.max(s, d);
    }

    /** 影响分层的危险因素（2018 指南口径）。 */
    public static FactorCollector riskFactors(AssessmentContext ctx) {
        FactorCollector c = new FactorCollector("HTN_RF", "心血管危险因素");
        Integer age = ctx.getAgeYears();
        if (age == null || !ctx.hasGender()) {
            c.unchecked(age == null ? InputField.BIRTHDAY : InputField.GENDER, "年龄（男>55 / 女>65）");
        } else if ((ctx.isMale() && age > 55) || (ctx.isFemale() && age > 65)) {
            c.hit("AGE", "年龄（男>55 / 女>65）", age + " 岁", SRC_ARCHIVE);
        }
        c.check(HypertensionRiskRules.smokingIncludingPassive(ctx), InputField.SMOKING, "SMOKING", "吸烟或被动吸烟",
                HypertensionRiskRules.smokingDetail(ctx), SRC_ARCHIVE);
        c.check(HypertensionRiskRules.impairedGlucose(ctx), InputField.GLUCOSE, "IMPAIRED_GLUCOSE",
                "糖耐量受损或空腹血糖异常", HypertensionRiskRules.glucoseDetail(ctx), SRC_METRIC);
        Boolean lipid = null;
        if (Fmt.ge(ctx.getTc(), "5.2") || Fmt.ge(ctx.getLdlC(), "3.4") || Fmt.lt(ctx.getHdlC(), "1.0")) {
            lipid = true;
        } else if (ctx.getTc() != null || ctx.getLdlC() != null || ctx.getHdlC() != null) {
            lipid = false;
        }
        c.check(lipid, InputField.LIPIDS, "DYSLIPIDEMIA", "血脂异常（TC≥5.2 / LDL-C≥3.4 / HDL-C<1.0）",
                HypertensionRiskRules.lipidDetail(ctx), SRC_LAB);
        c.check(ctx.isFamilyHistoryCollected() ? ctx.getFhEarlyAscvd() : null, InputField.FAMILY_HISTORY,
                "EARLY_CVD_FH", "早发心血管病家族史", null, SRC_ARCHIVE);
        boolean bmi28 = Fmt.ge(ctx.getBmi(), "28");
        Boolean central = ctx.centralObesity();
        if (bmi28 || Boolean.TRUE.equals(central)) {
            c.hit("OBESITY", "腹型肥胖或 BMI≥28",
                    bmi28 ? "BMI " + Fmt.num(ctx.getBmi()) : "腰围 " + Fmt.num(ctx.getWaistCm()) + " cm", SRC_METRIC);
        } else if (ctx.getBmi() == null && central == null) {
            c.unchecked(InputField.WEIGHT, "腹型肥胖或 BMI≥28");
        }
        c.fact(ctx, InputField.HCY_HIGH, "HCY", "高同型半胱氨酸（≥15 μmol/L）");
        return c;
    }

    /** 靶器官损害。 */
    public static FactorCollector targetOrganDamage(AssessmentContext ctx) {
        FactorCollector c = new FactorCollector("HTN_TOD", "靶器官损害");
        BigDecimal egfr = ctx.getEgfr();
        if (egfr != null && Fmt.between(egfr, "30", "59.99")) {
            c.hit("EGFR_30_59", "eGFR 30–59（慢性肾病 3 期）", Fmt.num(egfr), SRC_LAB);
        }
        BigDecimal cr = ctx.getCreatinine();
        if (cr != null && ctx.hasGender() && egfr == null
                && Fmt.between(cr, ctx.isMale() ? "115" : "107", ctx.isMale() ? "133" : "124")) {
            c.hit("CREATININE_MILD", "血肌酐轻度升高", Fmt.num(cr) + " μmol/L", SRC_LAB);
        }
        BigDecimal uacr = ctx.getUacr();
        if (uacr != null && Fmt.between(uacr, "30", "300")) {
            c.hit("UACR_30_300", "尿白蛋白/肌酐比 30–300 mg/g", Fmt.num(uacr), SRC_LAB);
        }
        if (egfr == null && uacr == null && cr == null) {
            c.unchecked(InputField.RENAL, "肾功能（eGFR / 尿白蛋白肌酐比）");
        }
        c.fact(ctx, InputField.LVH, "LVH", "左心室肥厚");
        c.fact(ctx, InputField.CAROTID_PLAQUE, "CAROTID", "颈动脉内中膜增厚或斑块");
        c.fact(ctx, InputField.PWV_HIGH, "PWV", "脉搏波传导速度增快");
        c.fact(ctx, InputField.ABI_LOW, "ABI", "踝臂指数 <0.9");
        return c;
    }

    /** 临床并发症（含有并发症的糖尿病、CKD≥4 期）。 */
    public static FactorCollector clinicalComplications(AssessmentContext ctx) {
        FactorCollector c = new FactorCollector("HTN_ACC", "临床并发症");
        String[][] illness = {
            {"CHD", "冠心病"}, {"STROKE", "脑血管病"}, {"HEART_FAILURE", "心力衰竭"},
            {"ATRIAL_FIBRILLATION", "心房颤动"}, {"CKD", "慢性肾病"},
        };
        for (String[] it : illness) {
            if (Boolean.TRUE.equals(ctx.hasIllness(it[0]))) {
                c.hit(it[0], it[1], "现有疾病", SRC_ARCHIVE);
            }
        }
        if (!ctx.isPresentIllnessCollected()) {
            c.unchecked(InputField.PRESENT_ILLNESS, "心脑血管病 / 慢性肾病（现有疾病）");
        }
        if (Fmt.lt(ctx.getEgfr(), "30")) {
            c.hit("EGFR_LT_30", "eGFR <30（慢性肾病 ≥4 期）", Fmt.num(ctx.getEgfr()), SRC_LAB);
        }
        if (Fmt.gt(ctx.getUacr(), "300")) {
            c.hit("UACR_GT_300", "尿白蛋白/肌酐比 >300 mg/g", Fmt.num(ctx.getUacr()), SRC_LAB);
        }
        c.fact(ctx, InputField.PERIPHERAL_ARTERY_DISEASE, "PAD", "外周血管疾病");
        c.fact(ctx, InputField.ADVANCED_RETINOPATHY, "RETINOPATHY", "视网膜病变（出血/渗出/视乳头水肿）");
        if (ctx.hasKnownDiabetes()) {
            Boolean dmc = diabetesWithComplications(ctx);
            c.check(dmc, InputField.DM_COMPLICATIONS, "DM_WITH_COMPLICATIONS", "有并发症的糖尿病",
                    dmc == Boolean.TRUE ? String.join("、", ctx.factList(InputField.DM_COMPLICATIONS)) : null,
                    FactorCollector.SRC_QUESTIONNAIRE);
        }
        return c;
    }

    /** 糖尿病并发症：问卷多选；未答为 null。 */
    public static Boolean diabetesWithComplications(AssessmentContext ctx) {
        List<String> list = ctx.factList(InputField.DM_COMPLICATIONS);
        if (list == null || list.isEmpty() || list.contains(InputField.Question.UNKNOWN)) {
            return null;
        }
        return list.stream().anyMatch(s -> !InputField.Question.NONE.equals(s));
    }
}
