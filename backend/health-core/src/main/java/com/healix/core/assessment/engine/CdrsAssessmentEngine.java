package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentKind;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.AssessmentItem;
import com.healix.core.assessment.support.CdrsSupplementalRisk;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 中国糖尿病风险评分表（CDRS），依据《中国2型糖尿病防治指南（2020）》评分表。
 *
 * <p>总分 0–51。高风险判定为并列「或」关系：评分≥25，或命中任一高危人群定义因素。
 * 评分≥25 时强烈建议 OGTT；仅命中高危因素时侧重定期监测与生活方式干预。
 */
@Component
public class CdrsAssessmentEngine implements AssessmentEngine {

    public static final String RULE_PACK = "CDRS-2020.2";
    private static final GuidelineRef GUIDELINE =
            new GuidelineRef("中国2型糖尿病防治指南", "2020", 2020);

    private static final Map<String, String> MISSING_LABELS = Map.of(
            "GENDER", "性别",
            "BIRTHDAY", "生日/年龄",
            "HEIGHT_WEIGHT", "身高体重（BMI）",
            "WAIST", "腰围",
            "BLOOD_PRESSURE_SYS", "收缩压",
            "FAMILY_HISTORY", "糖尿病家族史（父母/同胞/子女）");

    @Override
    public String code() {
        return AssessmentEngineCode.CDRS.name();
    }

    @Override
    public AssessmentKind kind() {
        return AssessmentKind.INCIDENT_RISK;
    }

    @Override
    public String diseaseCode() {
        return "diabetes";
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
        if (ctx.hasKnownDiabetes()) {
            return false;
        }
        Integer age = ctx.getAgeYears();
        if (age != null && (age < 20 || age > 74)) {
            return false;
        }
        return true;
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        List<String> missing = new ArrayList<>();
        if (!StringUtils.hasText(ctx.getGender()) || "UNKNOWN".equalsIgnoreCase(ctx.getGender())) {
            missing.add("GENDER");
        }
        if (ctx.getBirthday() == null || ctx.getAgeYears() == null) {
            missing.add("BIRTHDAY");
        }
        if (ctx.getHeightCm() == null || ctx.getWeightKg() == null || ctx.getBmi() == null) {
            missing.add("HEIGHT_WEIGHT");
        }
        if (ctx.getWaistCm() == null) {
            missing.add("WAIST");
        }
        if (ctx.getSbp() == null) {
            missing.add("BLOOD_PRESSURE_SYS");
        }
        if (!ctx.isFamilyHistoryCollected()) {
            missing.add("FAMILY_HISTORY");
        }
        if (!missing.isEmpty()) {
            String need = missing.stream()
                    .map(c -> MISSING_LABELS.getOrDefault(c, c))
                    .collect(Collectors.joining("、"));
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .missingFields(missing)
                    .advice("请补齐：" + need + "后再评估")
                    .guideline(GUIDELINE)
                    .rulePackVersion(RULE_PACK)
                    .extras(ruleExtras(ctx))
                    .build();
        }

        boolean male = "MALE".equalsIgnoreCase(ctx.getGender());
        int age = ctx.getAgeYears();
        List<AssessmentItem> items = new ArrayList<>();

        int agePts = scoreAge(age);
        items.add(item("AGE", "年龄", age + " 岁", agePts, ageBandLabel(age) + " → " + agePts + " 分"));

        int bmiPts = scoreBmi(ctx.getBmi());
        items.add(item(
                "BMI",
                "体重指数 BMI",
                ctx.getBmi().toPlainString() + " kg/m²",
                bmiPts,
                bmiBandLabel(ctx.getBmi()) + " → " + bmiPts + " 分"));

        int waistPts = scoreWaist(male, ctx.getWaistCm());
        items.add(item(
                "WAIST",
                "腰围",
                ctx.getWaistCm().toPlainString() + " cm（" + (male ? "男" : "女") + "）",
                waistPts,
                waistBandLabel(male, ctx.getWaistCm()) + " → " + waistPts + " 分"));

        int sbpPts = scoreSbp(ctx.getSbp());
        items.add(item(
                "SBP",
                "收缩压",
                ctx.getSbp().toPlainString() + " mmHg",
                sbpPts,
                sbpBandLabel(ctx.getSbp()) + " → " + sbpPts + " 分"));

        int fhPts = Boolean.TRUE.equals(ctx.getFirstDegreeDiabetesFamilyHistory()) ? 6 : 0;
        items.add(item(
                "FAMILY_HISTORY",
                "糖尿病家族史",
                fhPts > 0 ? "有（父母/同胞/子女）" : "无（父母/同胞/子女）",
                fhPts,
                fhPts > 0 ? "有一级亲属糖尿病史 → 6 分" : "无一级亲属糖尿病史 → 0 分"));

        int sexPts = male ? 2 : 0;
        items.add(item(
                "SEX",
                "性别",
                male ? "男" : "女",
                sexPts,
                male ? "男 → 2 分" : "女 → 0 分"));

        int total = agePts + bmiPts + waistPts + sbpPts + fhPts + sexPts;
        Map<String, Object> supplemental = CdrsSupplementalRisk.evaluate(ctx);
        boolean factorHighRisk = Boolean.TRUE.equals(supplemental.get("highRisk"));
        boolean scoreHighRisk = total >= 25;

        String level;
        String advice;
        if (scoreHighRisk) {
            // 评分≥25：风险信号较强，强烈建议 OGTT（即使同时命中高危因素，也以 OGTT 建议为主）
            level = "HIGH";
            advice = factorHighRisk
                    ? "总分 "
                            + total
                            + " 分（≥25）且命中高危因素，属糖尿病高风险人群，强烈建议行口服葡萄糖耐量试验（OGTT）排查是否已患病"
                    : "总分 " + total + " 分（≥25），属糖尿病高风险人群，强烈建议行口服葡萄糖耐量试验（OGTT）排查是否已患病";
        } else if (factorHighRisk) {
            // 仅命中高危因素、评分未达 25：仍标记高风险，管理重点在监测与生活方式
            level = "HIGH";
            advice = "总分 "
                    + total
                    + " 分（<25），但已命中高危人群定义因素，属糖尿病高风险人群；"
                    + "当前血糖可能仍正常，建议定期血糖监测并加强生活方式干预";
        } else if (total >= 15) {
            level = "MID";
            advice = "总分 " + total + " 分（15–24），未命中高危因素且未达评分高危阈值；建议关注生活方式并定期复测";
        } else {
            level = "LOW";
            advice = "总分 " + total + " 分（<15），未命中高危因素，属一般人群；建议保持健康生活方式并按指南定期筛查";
        }

        Map<String, Object> extras = ruleExtras(ctx, supplemental, scoreHighRisk, factorHighRisk);
        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(level)
                .score(BigDecimal.valueOf(total))
                .items(items)
                .advice(advice)
                .guideline(GUIDELINE)
                .rulePackVersion(RULE_PACK)
                .extras(extras)
                .build();
    }

    /** 供前端展示完整评分表、判定说明与高危因素命中。 */
    static Map<String, Object> ruleExtras(AssessmentContext ctx) {
        return ruleExtras(ctx, CdrsSupplementalRisk.evaluate(ctx), false, false);
    }

    static Map<String, Object> ruleExtras(
            AssessmentContext ctx,
            Map<String, Object> supplemental,
            boolean scoreHighRisk,
            boolean factorHighRisk) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("scoreRange", "0-51");
        extras.put("highRiskThreshold", 25);
        extras.put(
                "highRiskRule",
                "（评分总分≥25）或（命中任一高危人群定义）→ 糖尿病高风险人群；评分≥25 强烈建议 OGTT");
        extras.put("applicableAge", "20-74岁");
        extras.put("scoreHighRisk", scoreHighRisk);
        extras.put("factorHighRisk", factorHighRisk);
        extras.put(
                "supplementHighRiskNote",
                "高危人群定义（与评分表并列「或」判定）：年龄≥40岁；糖尿病前期史；超重/肥胖（BMI≥24）和/或中心性肥胖（男腰围≥90/女≥85）；"
                        + "久坐少动；一级亲属2型糖尿病史；妊娠期糖尿病史；高血压或正在降压治疗；"
                        + "血脂异常（HDL-C≤0.91 和/或 TG≥2.22）或正在调脂治疗；ASCVD；一过性类固醇糖尿病史；"
                        + "PCOS/黑棘皮症等；长期抗精神病药/抗抑郁药/他汀类用药");
        extras.put("supplementalRisk", supplemental);
        return extras;
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
        if (male) {
            if (w < 75) {
                return 0;
            }
            if (w < 80) {
                return 3;
            }
            if (w < 85) {
                return 5;
            }
            if (w < 90) {
                return 7;
            }
            if (w < 95) {
                return 8;
            }
            return 10;
        }
        if (w < 70) {
            return 0;
        }
        if (w < 75) {
            return 3;
        }
        if (w < 80) {
            return 5;
        }
        if (w < 85) {
            return 7;
        }
        if (w < 90) {
            return 8;
        }
        return 10;
    }

    static String waistBandLabel(boolean male, BigDecimal waistCm) {
        double w = waistCm.doubleValue();
        if (male) {
            if (w < 75) {
                return "男 <75.0 cm";
            }
            if (w < 80) {
                return "男 75.0–79.9 cm";
            }
            if (w < 85) {
                return "男 80.0–84.9 cm";
            }
            if (w < 90) {
                return "男 85.0–89.9 cm";
            }
            if (w < 95) {
                return "男 90.0–94.9 cm";
            }
            return "男 ≥95.0 cm";
        }
        if (w < 70) {
            return "女 <70.0 cm";
        }
        if (w < 75) {
            return "女 70.0–74.9 cm";
        }
        if (w < 80) {
            return "女 75.0–79.9 cm";
        }
        if (w < 85) {
            return "女 80.0–84.9 cm";
        }
        if (w < 90) {
            return "女 85.0–89.9 cm";
        }
        return "女 ≥90.0 cm";
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

    private static AssessmentItem item(
            String code, String label, String input, int points, String rationale) {
        return AssessmentItem.builder()
                .code(code)
                .label(label)
                .inputValue(input)
                .points(points)
                .rationale(rationale)
                .build();
    }
}
