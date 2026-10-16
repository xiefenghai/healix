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
import com.healix.core.assessment.support.AssessmentResult.Hit;
import com.healix.core.assessment.support.AssessmentResult.Item;
import com.healix.core.assessment.support.DiabetesControlLabelRules;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Input;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Label;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Metric;
import com.healix.core.assessment.support.DiabetesControlLabelRules.Output;
import com.healix.core.assessment.support.FactorCollector;
import com.healix.core.assessment.support.Fmt;
import com.healix.core.assessment.support.RuleSheet;
import com.healix.core.assessment.support.RuleSheet.Section;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 糖尿病严重等级分层（血糖控制分标：红 / 黄 / 绿 / 准绿 / 无标）。仅已确诊糖尿病适用。
 *
 * <p>统一等级：红 → 很高，黄 → 中，绿与准绿 → 低，无标 → 无数据。
 */
@Component
public class DiabetesSeverityEngine implements AssessmentEngine {

    private static final GuidelineRef GUIDELINE = new GuidelineRef("糖尿病血糖控制分标管理规则", "2024.1", 2024);
    private static final Set<InputField> INPUTS =
            EnumSet.of(InputField.BIRTHDAY, InputField.GLUCOSE, InputField.HBA1C, InputField.PRESENT_ILLNESS);
    private static final Map<String, String> HIT_LABELS = Map.of(
            "HYPO_14D", "近 14 天低血糖 ≥2 次",
            "HYPO_ARCHIVE_FALLBACK", "档案自填低血糖频繁（近 14 天无监测）",
            "A1C_GE_9", "糖化血红蛋白 ≥9%",
            "FBG_PBG_BOTH_HIGH", "空腹 ≥10 且非空腹 ≥13.9 mmol/L",
            "A1C_OVER_TARGET", "糖化血红蛋白未达标",
            "FBG_OVER_TARGET", "空腹血糖未达标",
            "PBG_OVER_TARGET", "非空腹血糖未达标");

    @Override
    public String code() {
        return AssessmentEngineCode.DIABETES_SEVERITY.name();
    }

    @Override
    public AssessmentCategory category() {
        return AssessmentCategory.SEVERITY;
    }

    @Override
    public AssessmentDisease disease() {
        return AssessmentDisease.DIABETES;
    }

    @Override
    public String label() {
        return "糖尿病血糖控制分层";
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
    public List<LevelDef> levels() {
        return List.of(
                new LevelDef(Label.RED.name(), "红标", AssessmentTier.VERY_HIGH),
                new LevelDef(Label.YELLOW.name(), "黄标", AssessmentTier.MID),
                new LevelDef(Label.GREEN.name(), "绿标", AssessmentTier.LOW),
                new LevelDef(Label.NEAR_GREEN.name(), "准绿标", AssessmentTier.LOW),
                new LevelDef(Label.NONE.name(), "无标（数据不足）", null));
    }

    @Override
    public Set<InputField> inputs() {
        return INPUTS;
    }

    @Override
    public Set<InputField> requiredInputs() {
        return Set.of();
    }

    @Override
    public boolean applicable(AssessmentContext ctx) {
        return ctx.hasKnownDiabetes();
    }

    @Override
    public AssessmentResult evaluate(AssessmentContext ctx) {
        LocalDateTime windowStart = ctx.getControlWindowStart();
        Metric a1c = windowMetric(ctx.getHba1c(), ctx.getHba1cRecordedAt(), windowStart);
        Metric fbg = windowMetric(ctx.getFastingGlucose(), ctx.getFastingGlucoseRecordedAt(), windowStart);
        Metric pbg = windowMetric(ctx.getPostprandialGlucose(), ctx.getPostprandialGlucoseRecordedAt(), windowStart);
        int comorbidity = ctx.getDiabetesComorbidityHits().size();
        Output out = DiabetesControlLabelRules.evaluate(new Input(
                ctx.getAgeYears(), comorbidity, ctx.isDiabetesEndStageChronic(), a1c, fbg, pbg,
                ctx.getHypoEvents14d(), ctx.isHypoArchiveFallbackHit()));

        List<Item> items = new ArrayList<>();
        items.add(new Item("TARGET_GROUP", "目标值分组", groupLabel(out.targetGroup()), null,
                "年龄 " + (ctx.getAgeYears() == null ? "未知" : ctx.getAgeYears()) + "，合并症 " + comorbidity
                        + (ctx.isDiabetesEndStageChronic() ? "，终末期慢病" : "")));
        items.add(new Item("A1C", "糖化血红蛋白", metric(a1c, "%"), null, "目标 <" + Fmt.num(out.targets().a1c()) + "%"));
        items.add(new Item("FBG", "空腹血糖", metric(fbg, " mmol/L"), null, "目标 <" + Fmt.num(out.targets().fbg()) + " mmol/L"));
        items.add(new Item("PBG", "非空腹血糖", metric(pbg, " mmol/L"), null, "目标 <" + Fmt.num(out.targets().pbg()) + " mmol/L"));
        items.add(new Item("HYPO_14D", "近 14 天低血糖", ctx.getHypoEvents14d() + " 次", null, "血糖 <4.0 mmol/L"));

        List<Hit> redHits = out.redHits().stream()
                .map(h -> new Hit(h, HIT_LABELS.getOrDefault(h, h), null, FactorCollector.SRC_METRIC)).toList();
        List<Hit> yellowHits = out.yellowHits().stream()
                .map(h -> new Hit(h, HIT_LABELS.getOrDefault(h, h), null, FactorCollector.SRC_METRIC)).toList();
        List<AssessmentResult.FactorGroup> groups = new ArrayList<>();
        if (!redHits.isEmpty()) {
            groups.add(new AssessmentResult.FactorGroup("RED", "红标条件", redHits, List.of()));
        }
        if (!yellowHits.isEmpty()) {
            groups.add(new AssessmentResult.FactorGroup("YELLOW", "黄标条件", yellowHits, List.of()));
        }

        boolean noData = out.label() == Label.NONE;
        String level = out.label().name();
        return AssessmentResult.builder()
                .status(noData ? AssessmentStatus.INCOMPLETE : AssessmentStatus.COMPLETE)
                .level(level)
                .tier(levelDef(level).tier())
                .advice(out.advice())
                .dimensions(List.of(new Dimension("LABEL", "控制分标", levelDef(level).label(),
                        levelDef(level).tier() == null ? null : levelDef(level).tier().name(), "近 90 天数据")))
                .items(items)
                .factorGroups(groups)
                .missing(noData
                        ? List.of(FactorCollector.missingOf(InputField.HBA1C), FactorCollector.missingOf(InputField.GLUCOSE))
                        : List.of())
                .build();
    }

    private static Metric windowMetric(BigDecimal value, LocalDateTime at, LocalDateTime windowStart) {
        if (value == null || (windowStart != null && at != null && at.isBefore(windowStart))) {
            return Metric.missing();
        }
        return Metric.of(value);
    }

    private static String metric(Metric m, String unit) {
        return !m.presentInWindow() || m.value() == null ? "近 90 天无数据" : Fmt.num(m.value()) + unit;
    }

    private static String groupLabel(DiabetesControlLabelRules.TargetGroup g) {
        return switch (g) {
            case GROUP_1 -> "① 年龄<65岁";
            case GROUP_2 -> "② 年龄≥65且合并症<3";
            case GROUP_3 -> "③ 年龄≥65且合并症≥3或终末期";
        };
    }

    @Override
    public RuleSheet ruleSheet() {
        return new RuleSheet(
                "适用已确诊糖尿病者，取近 90 天数据。优先级：无标 → 红标 → 黄标 → 绿标 → 准绿标。",
                List.of(
                        Section.table("目标值分组", List.of("分组", "HbA1c", "空腹血糖", "非空腹血糖"), List.of(
                                List.of("① 年龄<65岁", "<7.0%", "<7.0", "<10.0"),
                                List.of("② 年龄≥65且合并症<3", "<8.0%", "<8.3", "<10.0"),
                                List.of("③ 年龄≥65且合并症≥3或终末期慢病", "<8.5%", "<10.0", "<11.1"))),
                        Section.table("分标", List.of("分标", "条件", "统一等级"), List.of(
                                List.of("无标", "近 90 天无 HbA1c、空腹、非空腹任一数据", "无数据"),
                                List.of("红标", "近 14 天低血糖 ≥2 次；或 HbA1c ≥9%；或空腹 ≥10 且非空腹 ≥13.9", "很高"),
                                List.of("黄标", "任一指标未达分组目标", "中"),
                                List.of("绿标", "HbA1c 达标，或空腹与非空腹均达标", "低"),
                                List.of("准绿标", "无 HbA1c，仅空腹或仅非空腹且达标", "低")))),
                List.of("低血糖：血糖 <4.0 mmol/L；近 14 天无血糖监测时参考档案自填低血糖频次"));
    }
}
