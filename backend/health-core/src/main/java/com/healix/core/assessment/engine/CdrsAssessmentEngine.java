package com.healix.core.assessment.engine;

import static com.healix.core.assessment.support.FactorCollector.SRC_ARCHIVE;
import static com.healix.core.assessment.support.FactorCollector.SRC_DERIVED;
import static com.healix.core.assessment.support.FactorCollector.SRC_LAB;
import static com.healix.core.assessment.support.FactorCollector.SRC_MEDICATION;
import static com.healix.core.assessment.support.FactorCollector.SRC_METRIC;
import static com.healix.core.assessment.support.FactorCollector.SRC_QUESTIONNAIRE;

import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.Dimension;
import com.healix.core.assessment.support.AssessmentResult.Item;
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
 * 中国糖尿病风险评分（CDRS），《中国2型糖尿病防治指南（2020）》。
 *
 * <p>评分 ≥25，或命中任一高危人群因素 → 高风险（并列「或」）；15–24 → 中；其余 → 低。
 */
@Component
public class CdrsAssessmentEngine implements AssessmentEngine {

    public static final String RULE_PACK = "CDRS-2020.3";
    private static final GuidelineRef GUIDELINE = new GuidelineRef("中国2型糖尿病防治指南", "2020", 2020);
    private static final Set<InputField> REQUIRED = EnumSet.of(
            InputField.GENDER, InputField.BIRTHDAY, InputField.HEIGHT, InputField.WEIGHT,
            InputField.WAIST, InputField.BLOOD_PRESSURE, InputField.FAMILY_HISTORY);
    private static final Set<InputField> INPUTS = EnumSet.of(
            InputField.GENDER, InputField.BIRTHDAY, InputField.HEIGHT, InputField.WEIGHT, InputField.WAIST,
            InputField.BLOOD_PRESSURE, InputField.FAMILY_HISTORY, InputField.GLUCOSE, InputField.HBA1C,
            InputField.LIPIDS, InputField.EXERCISE, InputField.PRESENT_ILLNESS, InputField.PREDIABETES,
            InputField.GDM_HISTORY, InputField.STEROID_DIABETES, InputField.PCOS, InputField.ACANTHOSIS,
            InputField.PSYCH_DRUGS, InputField.ANTIHYPERTENSIVE, InputField.STATIN);

    @Override
    public String code() {
        return AssessmentEngineCode.CDRS.name();
    }

    @Override
    public AssessmentCategory category() {
        return AssessmentCategory.RISK;
    }

    @Override
    public AssessmentDisease disease() {
        return AssessmentDisease.DIABETES;
    }

    @Override
    public String label() {
        return "糖尿病患病风险（CDRS）";
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
        if (ctx.hasKnownDiabetes()) {
            return false;
        }
        Integer age = ctx.getAgeYears();
        return age == null || (age >= 20 && age <= 74);
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        FactorCollector risk = highRiskFactors(ctx);
        Suspicion suspicion = suspicion(ctx);
        List<Missing> missing = FactorCollector.missing(ctx, REQUIRED);
        if (!missing.isEmpty()) {
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .advice("缺少评分必需项，补齐后自动重新评估")
                    .missing(missing)
                    .factorGroups(List.of(risk.build()))
                    .suspicion(suspicion)
                    .build();
        }

        boolean male = ctx.isMale();
        int age = ctx.getAgeYears();
        List<Item> items = new ArrayList<>();
        int agePts = scoreAge(age);
        items.add(new Item("AGE", "年龄", age + " 岁", agePts, ageBandLabel(age)));
        int bmiPts = scoreBmi(ctx.getBmi());
        items.add(new Item("BMI", "BMI", Fmt.num(ctx.getBmi()) + " kg/m²", bmiPts, bmiBandLabel(ctx.getBmi())));
        int waistPts = scoreWaist(male, ctx.getWaistCm());
        items.add(new Item("WAIST", "腰围", Fmt.num(ctx.getWaistCm()) + " cm", waistPts, waistBandLabel(male, ctx.getWaistCm())));
        int sbpPts = scoreSbp(ctx.getSbp());
        items.add(new Item("SBP", "收缩压", Fmt.num(ctx.getSbp()) + " mmHg（" + Fmt.bpSource(ctx) + "）", sbpPts, sbpBandLabel(ctx.getSbp())));
        int fhPts = Boolean.TRUE.equals(ctx.getFhDiabetes()) ? 6 : 0;
        items.add(new Item("FAMILY_HISTORY", "糖尿病家族史", fhPts > 0 ? "有" : "无", fhPts, "一级亲属"));
        int sexPts = male ? 2 : 0;
        items.add(new Item("SEX", "性别", male ? "男" : "女", sexPts, null));
        int total = agePts + bmiPts + waistPts + sbpPts + fhPts + sexPts;

        boolean scoreHigh = total >= 25;
        boolean factorHigh = risk.hitCount() > 0;
        String level;
        String advice;
        if (scoreHigh) {
            level = "HIGH";
            advice = "总分 " + total + " 分（≥25），糖尿病高风险，建议安排医师评估是否进行口服葡萄糖耐量试验（OGTT）";
        } else if (factorHigh) {
            level = "HIGH";
            advice = "总分 " + total + " 分，但命中 " + risk.hitCount() + " 项高危因素，属糖尿病高风险人群；建议定期监测血糖并强化生活方式干预";
        } else if (total >= 15) {
            level = "MID";
            advice = "总分 " + total + " 分（15–24），建议关注生活方式并定期复测";
        } else {
            level = "LOW";
            advice = "总分 " + total + " 分（<15），建议保持健康生活方式，按指南定期筛查";
        }
        if (suspicion != null) {
            advice = suspicion.reason() + "；" + advice;
        }

        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(level)
                .tier(levelDef(level).tier())
                .score(BigDecimal.valueOf(total))
                .advice(advice)
                .dimensions(List.of(
                        new Dimension("SCORE", "CDRS 评分", total + " / 51", scoreHigh ? "HIGH" : total >= 15 ? "MID" : "LOW", "≥25 为高风险"),
                        new Dimension("FACTORS", "高危因素", factorHigh ? "命中 " + risk.hitCount() + " 项" : "未命中",
                                factorHigh ? "HIGH" : "LOW", "与评分并列「或」判定")))
                .items(items)
                .factorGroups(List.of(risk.build()))
                .suspicion(suspicion)
                .build();
    }

    /** 已达糖尿病诊断阈值的单次读数，提示确诊。 */
    static Suspicion suspicion(AssessmentContext ctx) {
        List<String> hits = new ArrayList<>();
        if (Fmt.ge(ctx.getFastingGlucose(), "7.0")) {
            hits.add("空腹血糖 " + Fmt.num(ctx.getFastingGlucose()) + " mmol/L");
        }
        if (Fmt.ge(ctx.getPostprandialGlucose(), "11.1")) {
            hits.add("餐后血糖 " + Fmt.num(ctx.getPostprandialGlucose()) + " mmol/L");
        }
        if (Fmt.ge(ctx.getHba1c(), "6.5")) {
            hits.add("糖化血红蛋白 " + Fmt.num(ctx.getHba1c()) + "%");
        }
        if (Boolean.TRUE.equals(ctx.onDrug("DM"))) {
            hits.add("正在使用降糖药");
        }
        if (hits.isEmpty()) {
            return null;
        }
        return new Suspicion(
                AssessmentDisease.DIABETES.code(),
                AssessmentDisease.DIABETES.label(),
                String.join("、", hits) + "，疑似已患糖尿病，建议确诊并建立糖尿病档案");
    }

    static FactorCollector highRiskFactors(AssessmentContext ctx) {
        FactorCollector c = new FactorCollector("DM_HIGH_RISK", "糖尿病高危人群因素");
        Integer age = ctx.getAgeYears();
        if (age == null) {
            c.unchecked(InputField.BIRTHDAY, "年龄≥40岁");
        } else if (age >= 40) {
            c.hit("AGE_GE_40", "年龄≥40岁", age + " 岁", SRC_ARCHIVE);
        }
        c.check(ctx.prediabetes(), InputField.PREDIABETES, "PREDIABETES", "糖尿病前期史", null, SRC_ARCHIVE);

        boolean bmi24 = Fmt.ge(ctx.getBmi(), "24");
        Boolean central = ctx.centralObesity();
        if (bmi24 || Boolean.TRUE.equals(central)) {
            String detail = (bmi24 ? "BMI " + Fmt.num(ctx.getBmi()) : "")
                    + (Boolean.TRUE.equals(central) ? (bmi24 ? "；" : "") + "腰围 " + Fmt.num(ctx.getWaistCm()) + " cm" : "");
            c.hit("OVERWEIGHT_OR_CENTRAL", "超重/肥胖或中心性肥胖", detail, SRC_METRIC);
        } else if (ctx.getBmi() == null && central == null) {
            c.unchecked(InputField.WEIGHT, "超重/肥胖或中心性肥胖");
        }
        c.check(ctx.sedentary(), InputField.EXERCISE, "SEDENTARY", "久坐少动", "基本不运动", SRC_ARCHIVE);
        c.check(ctx.isFamilyHistoryCollected() ? ctx.getFhDiabetes() : null,
                InputField.FAMILY_HISTORY, "FH_DIABETES", "一级亲属糖尿病史", null, SRC_ARCHIVE);
        if (!ctx.isMale()) {
            c.fact(ctx, InputField.GDM_HISTORY, "GDM", "妊娠期糖尿病史");
        }

        Boolean htn = null;
        String htnDetail = null;
        String htnSrc = SRC_ARCHIVE;
        if (ctx.hasKnownHypertension()) {
            htn = true;
            htnDetail = "已确诊高血压";
        } else if (Fmt.ge(ctx.getSbp(), "140") || Fmt.ge(ctx.getDbp(), "90")) {
            htn = true;
            htnDetail = "血压 " + Fmt.bp(ctx);
            htnSrc = SRC_METRIC;
        } else if (Boolean.TRUE.equals(ctx.antihypertensiveTherapy())) {
            htn = true;
            htnDetail = "正在降压治疗";
            htnSrc = ctx.onDrug("BP") ? SRC_MEDICATION : SRC_QUESTIONNAIRE;
        } else if (ctx.getSbp() != null) {
            htn = false;
        }
        c.check(htn, InputField.BLOOD_PRESSURE, "HYPERTENSION", "高血压或正在降压治疗", htnDetail, htnSrc);

        Boolean lipid = null;
        String lipidDetail = null;
        String lipidSrc = SRC_LAB;
        if (Fmt.le(ctx.getHdlC(), "0.91") || Fmt.ge(ctx.getTg(), "2.22")) {
            lipid = true;
            lipidDetail = "HDL-C " + Fmt.num(ctx.getHdlC()) + " / TG " + Fmt.num(ctx.getTg()) + " mmol/L";
        } else if (Boolean.TRUE.equals(ctx.lipidTherapy())) {
            lipid = true;
            lipidDetail = "正在调脂治疗";
            lipidSrc = ctx.onDrug("LIPID") ? SRC_MEDICATION : SRC_QUESTIONNAIRE;
        } else if (ctx.getHdlC() != null || ctx.getTg() != null) {
            lipid = false;
        }
        c.check(lipid, InputField.LIPIDS, "DYSLIPIDEMIA", "血脂异常或正在调脂治疗", lipidDetail, lipidSrc);

        Boolean ascvd = null;
        if (ctx.hasIllness("CHD") == Boolean.TRUE || ctx.hasIllness("STROKE") == Boolean.TRUE) {
            ascvd = true;
        } else if (ctx.isPresentIllnessCollected()) {
            ascvd = false;
        }
        c.check(ascvd, InputField.PRESENT_ILLNESS, "ASCVD", "动脉粥样硬化性心血管病", "冠心病/脑卒中", SRC_ARCHIVE);
        c.fact(ctx, InputField.STEROID_DIABETES, "STEROID_DM", "一过性类固醇糖尿病史");
        if (!ctx.isMale()) {
            c.fact(ctx, InputField.PCOS, "PCOS", "多囊卵巢综合征");
        }
        c.fact(ctx, InputField.ACANTHOSIS, "ACANTHOSIS", "黑棘皮症");
        c.fact(ctx, InputField.PSYCH_DRUGS, "PSYCH_DRUGS", "长期抗精神病药/抗抑郁药");
        if (Boolean.TRUE.equals(ctx.lipidTherapy()) && !c.has("STATIN")) {
            c.hit("STATIN", "他汀类药物治疗", ctx.onDrug("LIPID") ? "在用调脂药" : "问卷：是", SRC_DERIVED);
        }
        return c;
    }

    @Override
    public RuleSheet ruleSheet() {
        return new RuleSheet(
                "适用 20–74 岁未确诊糖尿病者。总分 0–51；评分 ≥25 或命中任一高危因素 → 高风险，15–24 → 中风险，<15 → 低风险。",
                List.of(
                        Section.table("年龄", List.of("年龄（岁）", "分值"), List.of(
                                List.of("20–24", "0"), List.of("25–34", "4"), List.of("35–39", "8"), List.of("40–44", "11"),
                                List.of("45–49", "12"), List.of("50–54", "13"), List.of("55–59", "15"), List.of("60–64", "16"),
                                List.of("65–74", "18"))),
                        Section.table("BMI（kg/m²）", List.of("BMI", "分值"), List.of(
                                List.of("<22.0", "0"), List.of("22.0–23.9", "1"), List.of("24.0–29.9", "3"), List.of("≥30.0", "5"))),
                        Section.table("腰围（cm）", List.of("男", "女", "分值"), List.of(
                                List.of("<75.0", "<70.0", "0"), List.of("75.0–79.9", "70.0–74.9", "3"),
                                List.of("80.0–84.9", "75.0–79.9", "5"), List.of("85.0–89.9", "80.0–84.9", "7"),
                                List.of("90.0–94.9", "85.0–89.9", "8"), List.of("≥95.0", "≥90.0", "10"))),
                        Section.table("收缩压（mmHg）", List.of("收缩压", "分值"), List.of(
                                List.of("<110", "0"), List.of("110–119", "1"), List.of("120–129", "3"), List.of("130–139", "6"),
                                List.of("140–149", "7"), List.of("150–159", "8"), List.of("≥160", "10"))),
                        Section.table("其他", List.of("项目", "分值"), List.of(
                                List.of("糖尿病家族史（父母、同胞、子女）", "有 6 / 无 0"), List.of("性别", "男 2 / 女 0"))),
                        Section.bullets("高危人群因素（命中任一即高风险）", List.of(
                                "年龄≥40岁", "糖尿病前期史", "超重/肥胖（BMI≥24）和/或中心性肥胖（男腰围≥90、女≥85 cm）",
                                "久坐少动", "一级亲属有 2 型糖尿病", "妊娠期糖尿病史", "高血压或正在降压治疗",
                                "血脂异常（HDL-C≤0.91 和/或 TG≥2.22 mmol/L）或正在调脂治疗", "动脉粥样硬化性心血管病",
                                "一过性类固醇糖尿病史", "多囊卵巢综合征或黑棘皮症", "长期抗精神病药/抗抑郁药或他汀类药物治疗"))),
                List.of("收缩压取近 4 周均值（至少 2 次）；不足时取最新一次",
                        "空腹血糖≥7.0、餐后血糖≥11.1 或 HbA1c≥6.5% 提示疑似已患病，建议确诊建档"));
    }

    static int scoreAge(int age) {
        if (age <= 24) {
            return 0;
        }
        if (age <= 34) {
            return 4;
        }
        if (age <= 39) {
            return 8;
        }
        if (age <= 44) {
            return 11;
        }
        if (age <= 49) {
            return 12;
        }
        if (age <= 54) {
            return 13;
        }
        if (age <= 59) {
            return 15;
        }
        if (age <= 64) {
            return 16;
        }
        return 18;
    }

    static String ageBandLabel(int age) {
        if (age <= 24) {
            return "20–24 岁";
        }
        if (age <= 34) {
            return "25–34 岁";
        }
        if (age <= 39) {
            return "35–39 岁";
        }
        if (age <= 44) {
            return "40–44 岁";
        }
        if (age <= 49) {
            return "45–49 岁";
        }
        if (age <= 54) {
            return "50–54 岁";
        }
        if (age <= 59) {
            return "55–59 岁";
        }
        if (age <= 64) {
            return "60–64 岁";
        }
        return "65–74 岁";
    }

    static int scoreBmi(BigDecimal bmi) {
        double v = bmi.doubleValue();
        if (v < 22.0) {
            return 0;
        }
        if (v < 24.0) {
            return 1;
        }
        if (v < 30.0) {
            return 3;
        }
        return 5;
    }

    static String bmiBandLabel(BigDecimal bmi) {
        double v = bmi.doubleValue();
        if (v < 22.0) {
            return "BMI <22.0";
        }
        if (v < 24.0) {
            return "BMI 22.0–23.9";
        }
        if (v < 30.0) {
            return "BMI 24.0–29.9";
        }
        return "BMI ≥30.0";
    }

    static int scoreWaist(boolean male, BigDecimal waistCm) {
        double w = waistCm.doubleValue();
        double base = male ? 75 : 70;
        if (w < base) {
            return 0;
        }
        if (w < base + 5) {
            return 3;
        }
        if (w < base + 10) {
            return 5;
        }
        if (w < base + 15) {
            return 7;
        }
        if (w < base + 20) {
            return 8;
        }
        return 10;
    }

    static String waistBandLabel(boolean male, BigDecimal waistCm) {
        double w = waistCm.doubleValue();
        int base = male ? 75 : 70;
        String sex = male ? "男 " : "女 ";
        if (w < base) {
            return sex + "<" + base + ".0 cm";
        }
        for (int i = 0; i < 4; i++) {
            int lo = base + i * 5;
            if (w < lo + 5) {
                return sex + lo + ".0–" + (lo + 4) + ".9 cm";
            }
        }
        return sex + "≥" + (base + 20) + ".0 cm";
    }

    static int scoreSbp(BigDecimal sbp) {
        double v = sbp.doubleValue();
        if (v < 110) {
            return 0;
        }
        if (v < 120) {
            return 1;
        }
        if (v < 130) {
            return 3;
        }
        if (v < 140) {
            return 6;
        }
        if (v < 150) {
            return 7;
        }
        if (v < 160) {
            return 8;
        }
        return 10;
    }

    static String sbpBandLabel(BigDecimal sbp) {
        double v = sbp.doubleValue();
        if (v < 110) {
            return "SBP <110";
        }
        if (v < 120) {
            return "SBP 110–119";
        }
        if (v < 130) {
            return "SBP 120–129";
        }
        if (v < 140) {
            return "SBP 130–139";
        }
        if (v < 150) {
            return "SBP 140–149";
        }
        if (v < 160) {
            return "SBP 150–159";
        }
        return "SBP ≥160";
    }
}
