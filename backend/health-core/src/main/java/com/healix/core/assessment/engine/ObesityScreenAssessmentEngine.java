package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentKind;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.AssessmentItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 肥胖症筛查/分层（《肥胖症诊疗指南（2024年版）》）。
 *
 * <p>并列输出：BMI 诊断（正常/超重/肥胖症）+ 肥胖严重度分层 + 中心性肥胖独立标记。
 */
@Component
public class ObesityScreenAssessmentEngine implements AssessmentEngine {

    public static final String RULE_PACK = "OBESITY_SCREEN-2024.2";
    private static final GuidelineRef GUIDELINE =
            new GuidelineRef("肥胖症诊疗指南", "2024", 2024);

    public static final String DIAG_UNDERWEIGHT = "UNDERWEIGHT";
    public static final String DIAG_NORMAL = "NORMAL";
    public static final String DIAG_OVERWEIGHT = "OVERWEIGHT";
    public static final String DIAG_OBESITY = "OBESITY";

    @Override
    public String code() {
        return AssessmentEngineCode.OBESITY_SCREEN.name();
    }

    @Override
    public AssessmentKind kind() {
        return AssessmentKind.INCIDENT_RISK;
    }

    @Override
    public String diseaseCode() {
        return "obesity";
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
        if (ctx.hasKnownObesity()) {
            return false;
        }
        // 儿童青少年需年龄别百分位，本期不做成人固定阈值硬套
        Integer age = ctx.getAgeYears();
        return age == null || age >= 18;
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        List<String> missing = new ArrayList<>();
        if (ctx.getBmi() == null) {
            missing.add("HEIGHT_WEIGHT");
        }
        if (!StringUtils.hasText(ctx.getGender()) || "UNKNOWN".equalsIgnoreCase(ctx.getGender())) {
            missing.add("GENDER");
        }
        if (!missing.isEmpty()) {
            return AssessmentResult.builder()
                    .status(AssessmentStatus.INCOMPLETE)
                    .missingFields(missing)
                    .advice("请补齐性别与身高体重（BMI）后再做肥胖评估；腰围用于中心性肥胖独立标记")
                    .guideline(GUIDELINE)
                    .rulePackVersion(RULE_PACK)
                    .extras(Map.of(
                            "guidelineRef", "《肥胖症诊疗指南（2024年版）》",
                            "note", "中国成人 BMI≥28 诊断肥胖症（严于 WHO≥30）"))
                    .build();
        }

        boolean male = "MALE".equalsIgnoreCase(ctx.getGender());
        String diagnosis = diagnoseByBmi(ctx.getBmi());
        String severity = severityByBmi(ctx.getBmi());
        Boolean central = null;
        String waistBand = null;
        List<String> unchecked = new ArrayList<>();
        if (ctx.getWaistCm() == null) {
            unchecked.add("腰围（中心性肥胖未测）");
            unchecked.add("腰臀比 WHR（未采）");
        } else {
            central = isCentralObesity(male, ctx.getWaistCm());
            waistBand = waistBand(male, ctx.getWaistCm());
        }
        unchecked.add("体脂率（档案/指标未结构化；男>25% / 女>30% 可辅助诊断）");

        List<AssessmentItem> items = new ArrayList<>();
        items.add(AssessmentItem.builder()
                .code("BMI")
                .label("体重指数 BMI")
                .inputValue(ctx.getBmi().toPlainString() + " kg/m²")
                .points(null)
                .rationale(diagnosisLabel(diagnosis) + "；" + severityLabel(severity))
                .build());
        items.add(AssessmentItem.builder()
                .code("DIAGNOSIS")
                .label("BMI 诊断")
                .inputValue(diagnosisLabel(diagnosis))
                .points(null)
                .rationale(DIAG_OBESITY.equals(diagnosis)
                        ? "BMI≥28 诊断为肥胖症（中国标准）"
                        : DIAG_OVERWEIGHT.equals(diagnosis)
                                ? "BMI 24.0–<28.0 为超重"
                                : "未达肥胖症诊断阈值")
                .build());
        if (DIAG_OBESITY.equals(diagnosis)) {
            items.add(AssessmentItem.builder()
                    .code("SEVERITY")
                    .label("肥胖严重度")
                    .inputValue(severityLabel(severity))
                    .points(null)
                    .rationale("用于指导分层干预（轻/中/重/极重）")
                    .build());
        }
        items.add(AssessmentItem.builder()
                .code("CENTRAL")
                .label("中心性肥胖")
                .inputValue(central == null ? "未测" : (central ? "是" : "否"))
                .points(null)
                .rationale(central == null
                        ? "请补录腰围"
                        : (central
                                ? "腰围达中心性肥胖阈值（男≥90 / 女≥85 cm）"
                                : waistBandLabel(waistBand) + "；未达中心性肥胖阈值"))
                .build());
        if (ctx.getWaistCm() != null) {
            items.add(AssessmentItem.builder()
                    .code("WAIST")
                    .label("腰围")
                    .inputValue(ctx.getWaistCm().toPlainString() + " cm（" + (male ? "男" : "女") + "）")
                    .points(null)
                    .rationale(waistBandLabel(waistBand))
                    .build());
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("guidelineRef", "《肥胖症诊疗指南（2024年版）》");
        extras.put("diagnosis", diagnosis);
        extras.put("diagnosisLabel", diagnosisLabel(diagnosis));
        extras.put("bmiCategory", severity);
        extras.put("severityLabel", severityLabel(severity));
        extras.put("centralObesity", central);
        extras.put("waistBand", waistBand);
        extras.put("centralObesityThresholdCm", male ? 90 : 85);
        extras.put("normalWaistThresholdCm", male ? 85 : 80);
        extras.put("unchecked", unchecked);
        extras.put(
                "dimensions",
                List.of(
                        Map.of(
                                "code", "DIAGNOSIS",
                                "label", "BMI 诊断",
                                "value", diagnosisLabel(diagnosis),
                                "hint", "全身性肥胖主标准（中国成人）"),
                        Map.of(
                                "code", "SEVERITY",
                                "label", "肥胖严重度",
                                "value",
                                        DIAG_OBESITY.equals(diagnosis)
                                                ? severityLabel(severity)
                                                : "—",
                                "hint", "确诊肥胖后的分层干预依据"),
                        Map.of(
                                "code",
                                "CENTRAL",
                                "label",
                                "中心性肥胖",
                                "value",
                                central == null ? "未测" : (central ? "是" : "否"),
                                "hint",
                                "与 BMI 诊断可并存的独立标记")));

        String advice = buildAdvice(diagnosis, severity, central);

        // level 用细分层级，便于标签着色；诊断结论在 extras.diagnosis
        return AssessmentResult.builder()
                .status(AssessmentStatus.COMPLETE)
                .level(severity)
                .score(ctx.getBmi())
                .items(items)
                .advice(advice)
                .guideline(GUIDELINE)
                .rulePackVersion(RULE_PACK)
                .extras(extras)
                .build();
    }

    /** 产品主诊断：正常 / 超重 / 肥胖症（另保留偏瘦）。 */
    static String diagnoseByBmi(BigDecimal bmi) {
        double v = bmi.doubleValue();
        if (v < 18.5) {
            return DIAG_UNDERWEIGHT;
        }
        if (v < 24.0) {
            return DIAG_NORMAL;
        }
        if (v < 28.0) {
            return DIAG_OVERWEIGHT;
        }
        return DIAG_OBESITY;
    }

    /** 细分层：含肥胖轻/中/重/极重。 */
    static String severityByBmi(BigDecimal bmi) {
        double v = bmi.doubleValue();
        if (v < 18.5) {
            return "UNDERWEIGHT";
        }
        if (v < 24.0) {
            return "NORMAL";
        }
        if (v < 28.0) {
            return "OVERWEIGHT";
        }
        if (v < 32.5) {
            return "MILD_OBESITY";
        }
        if (v < 37.5) {
            return "MODERATE_OBESITY";
        }
        if (v < 50.0) {
            return "SEVERE_OBESITY";
        }
        return "EXTREME_OBESITY";
    }

    /** 兼容旧调用名。 */
    static String classifyBmi(BigDecimal bmi) {
        return severityByBmi(bmi);
    }

    static boolean isCentralObesity(boolean male, BigDecimal waistCm) {
        double w = waistCm.doubleValue();
        return male ? w >= 90 : w >= 85;
    }

    /** NORMAL / ELEVATED / CENTRAL */
    static String waistBand(boolean male, BigDecimal waistCm) {
        double w = waistCm.doubleValue();
        double normalMax = male ? 85 : 80;
        double centralMin = male ? 90 : 85;
        if (w < normalMax) {
            return "NORMAL";
        }
        if (w < centralMin) {
            return "ELEVATED";
        }
        return "CENTRAL";
    }

    static String diagnosisLabel(String diagnosis) {
        return switch (diagnosis) {
            case DIAG_UNDERWEIGHT -> "偏瘦";
            case DIAG_NORMAL -> "正常";
            case DIAG_OVERWEIGHT -> "超重";
            case DIAG_OBESITY -> "肥胖症";
            default -> diagnosis;
        };
    }

    static String severityLabel(String level) {
        return switch (level) {
            case "UNDERWEIGHT" -> "偏瘦（BMI <18.5）";
            case "NORMAL" -> "正常（18.5–<24.0）";
            case "OVERWEIGHT" -> "超重（24.0–<28.0）";
            case "MILD_OBESITY" -> "轻度肥胖（28.0–<32.5）";
            case "MODERATE_OBESITY" -> "中度肥胖（32.5–<37.5）";
            case "SEVERE_OBESITY" -> "重度肥胖（37.5–<50）";
            case "EXTREME_OBESITY" -> "极重度肥胖（≥50）";
            default -> level;
        };
    }

    static String waistBandLabel(String band) {
        if (band == null) {
            return "腰围未测";
        }
        return switch (band) {
            case "NORMAL" -> "腰围正常（男<85 / 女<80 cm）";
            case "ELEVATED" -> "腰围增高但未达中心性肥胖阈值";
            case "CENTRAL" -> "中心性肥胖（男≥90 / 女≥85 cm）";
            default -> band;
        };
    }

    private static String buildAdvice(String diagnosis, String severity, Boolean central) {
        boolean centralYes = Boolean.TRUE.equals(central);
        if (DIAG_OBESITY.equals(diagnosis)) {
            String base = "BMI 达肥胖症诊断标准（≥28），严重度：" + severityLabel(severity) + "。";
            if (centralYes) {
                return base + "同时存在中心性肥胖，代谢风险更高，建议结合临床评估制定减重与代谢管理计划";
            }
            return base + "建议结合临床评估制定减重与分层干预计划";
        }
        if (DIAG_OVERWEIGHT.equals(diagnosis)) {
            if (centralYes) {
                return "超重且合并中心性肥胖，建议强化生活方式干预并定期复测体重与腰围";
            }
            return "BMI 属于超重（24.0–<28.0），建议生活方式干预，防止进展为肥胖症";
        }
        if (DIAG_UNDERWEIGHT.equals(diagnosis)) {
            return "BMI 偏低，建议关注营养与体重管理";
        }
        if (centralYes) {
            return "BMI 正常但存在中心性肥胖，建议控制腰围并关注代谢指标";
        }
        return "体重与腰围处于相对理想范围，建议保持健康生活方式";
    }
}
