package com.healix.core.assessment.engine;

import com.healix.core.assessment.catalog.AssessmentEngineCode;
import com.healix.core.assessment.catalog.AssessmentKind;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.AssessmentResult.AssessmentItem;
import com.healix.core.assessment.support.DiabetesControlLabelRules;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Input;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Label;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Metric;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Output;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 糖尿病患者血糖控制分标（红/黄/绿/准绿/无标）。
 *
 * <p>仅对已确诊糖尿病患者适用；与 CDRS 发病风险评分互斥。
 */
@Component
public class DiabetesControlLabelEngine implements AssessmentEngine {

    private static final GuidelineRef GUIDELINE =
            new GuidelineRef("糖尿病血糖控制分标管理规则", "2024.1", 2024);

    @Override
    public String code() {
        return AssessmentEngineCode.DIABETES_CONTROL_LABEL.name();
    }

    @Override
    public AssessmentKind kind() {
        return AssessmentKind.CONTROL_STATUS;
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
        return DiabetesControlLabelRules.RULE_PACK;
    }

    @Override
    public boolean applicable(AssessmentContext ctx) {
        return ctx.hasKnownDiabetes();
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        Metric a1c = windowMetric(ctx.getHba1c(), ctx.getHba1cRecordedAt(), ctx.getControlLabelWindowStart());
        Metric fbg = windowMetric(
                ctx.getFastingGlucose(), ctx.getFastingGlucoseRecordedAt(), ctx.getControlLabelWindowStart());
        Metric pbg = windowMetric(
                ctx.getPostprandialGlucose(),
                ctx.getPostprandialGlucoseRecordedAt(),
                ctx.getControlLabelWindowStart());

        Input input = new Input(
                ctx.getAgeYears(),
                ctx.getDiabetesComorbidityCount(),
                ctx.isDiabetesEndStageChronic(),
                a1c,
                fbg,
                pbg,
                ctx.getHypoEvents14d(),
                ctx.isHypoArchiveFallbackHit());

        Output out = DiabetesControlLabelRules.evaluate(input);

        List<AssessmentItem> items = new ArrayList<>();
        items.add(item(
                "TARGET_GROUP",
                "目标值分组",
                groupLabel(out.targetGroup()),
                null,
                "年龄="
                        + (ctx.getAgeYears() == null ? "未知" : ctx.getAgeYears())
                        + "，合并症="
                        + ctx.getDiabetesComorbidityCount()
                        + "，终末期="
                        + ctx.isDiabetesEndStageChronic()));
        items.add(item(
                "A1C",
                "糖化血红蛋白",
                formatMetric(a1c, "%"),
                null,
                "目标 < " + out.targets().a1c() + "%"));
        items.add(item(
                "FBG",
                "空腹血糖",
                formatMetric(fbg, " mmol/L"),
                null,
                "目标 < " + out.targets().fbg() + " mmol/L"));
        items.add(item(
                "PBG",
                "非空腹血糖",
                formatMetric(pbg, " mmol/L"),
                null,
                "目标 < " + out.targets().pbg() + " mmol/L"));
        items.add(item(
                "HYPO_14D",
                "近14天低血糖次数",
                String.valueOf(ctx.getHypoEvents14d()),
                null,
                ctx.isHypoArchiveFallbackHit() ? "监测不足，档案自填兜底命中" : "血糖 <4.0 mmol/L"));

        Map<String, Object> extras = new LinkedHashMap<>(out.extras());
        extras.put("comorbidityHits", ctx.getDiabetesComorbidityHits());
        extras.put(
                "hypoSource",
                ctx.getHypoEvents14d() >= 2
                        ? "VITALS"
                        : (ctx.isHypoArchiveFallbackHit() ? "ARCHIVE_FALLBACK" : "NONE"));
        extras.put("label", out.label().name());
        extras.put("labelLabel", labelDisplay(out.label()));

        List<String> missing = new ArrayList<>();
        if (Boolean.TRUE.equals(extras.get("incompleteCombo"))) {
            missing.add("GLUCOSE_COMBO");
        }

        AssessmentStatus status =
                out.label() == Label.NONE && !a1c.presentInWindow() && !fbg.presentInWindow() && !pbg.presentInWindow()
                        ? AssessmentStatus.COMPLETE
                        : (missing.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.INCOMPLETE);

        return AssessmentResult.builder()
                .status(status)
                .level(out.label().name())
                .score(null)
                .advice(out.advice())
                .items(items)
                .missingFields(missing)
                .guideline(GUIDELINE)
                .rulePackVersion(DiabetesControlLabelRules.RULE_PACK)
                .extras(extras)
                .build();
    }

    private static Metric windowMetric(BigDecimal value, LocalDateTime at, LocalDateTime windowStart) {
        if (value == null) {
            return Metric.missing();
        }
        if (windowStart != null && at != null && at.isBefore(windowStart)) {
            return Metric.missing();
        }
        // 有值但无时间：保守视为窗口内可用
        return Metric.of(value);
    }

    private static AssessmentItem item(
            String code, String label, String inputValue, Integer points, String rationale) {
        return AssessmentItem.builder()
                .code(code)
                .label(label)
                .inputValue(inputValue)
                .points(points)
                .rationale(rationale)
                .build();
    }

    private static String formatMetric(Metric m, String unit) {
        if (!m.presentInWindow() || m.value() == null) {
            return "窗口内无数据";
        }
        return m.value().stripTrailingZeros().toPlainString() + unit;
    }

    private static String groupLabel(DiabetesControlLabelRules.TargetGroup g) {
        return switch (g) {
            case GROUP_1 -> "① 年龄<65岁";
            case GROUP_2 -> "② 年龄≥65且合并症<3";
            case GROUP_3 -> "③ 年龄≥65且合并症≥3或终末期";
        };
    }

    static String labelDisplay(Label label) {
        return switch (label) {
            case NONE -> "无标";
            case RED -> "红标";
            case YELLOW -> "黄标";
            case GREEN -> "绿标";
            case NEAR_GREEN -> "准绿标";
        };
    }
}
