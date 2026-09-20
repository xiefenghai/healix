package com.healix.core.assessment.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 糖尿病患者血糖控制分标规则（与 CDRS 发病风险正交）。
 *
 * <p>优先级：无标 → 红标 → 黄标 → 绿标 → 准绿标。
 */
public final class DiabetesControlLabelRules {

    public static final String RULE_PACK = "DM-LABEL-2024.1";

    public enum Label {
        NONE,
        RED,
        YELLOW,
        GREEN,
        NEAR_GREEN
    }

    public enum TargetGroup {
        GROUP_1,
        GROUP_2,
        GROUP_3
    }

    public record Targets(BigDecimal a1c, BigDecimal fbg, BigDecimal pbg) {}

    public record Metric(BigDecimal value, boolean presentInWindow) {
        public static Metric missing() {
            return new Metric(null, false);
        }

        public static Metric of(BigDecimal value) {
            return value == null ? missing() : new Metric(value, true);
        }
    }

    public record Input(
            Integer ageYears,
            int comorbidityCount,
            boolean endStageChronic,
            Metric a1c,
            Metric fbg,
            Metric pbg,
            int hypo14dCount,
            boolean hypoArchiveFallbackHit) {}

    public record Output(
            Label label,
            TargetGroup targetGroup,
            Targets targets,
            List<String> redHits,
            List<String> yellowHits,
            String advice,
            String adviceCode,
            Map<String, Object> extras) {}

    private DiabetesControlLabelRules() {}

    public static TargetGroup resolveGroup(Integer ageYears, int comorbidityCount, boolean endStage) {
        if (ageYears == null || ageYears < 65) {
            return TargetGroup.GROUP_1;
        }
        if (endStage || comorbidityCount >= 3) {
            return TargetGroup.GROUP_3;
        }
        return TargetGroup.GROUP_2;
    }

    public static Targets targetsOf(TargetGroup group) {
        return switch (group) {
            case GROUP_1 -> new Targets(bd("7.0"), bd("7.0"), bd("10.0"));
            case GROUP_2 -> new Targets(bd("8.0"), bd("8.3"), bd("10.0"));
            case GROUP_3 -> new Targets(bd("8.5"), bd("10.0"), bd("11.1"));
        };
    }

    public static Output evaluate(Input in) {
        TargetGroup group = resolveGroup(in.ageYears(), in.comorbidityCount(), in.endStageChronic());
        Targets targets = targetsOf(group);
        List<String> redHits = new ArrayList<>();
        List<String> yellowHits = new ArrayList<>();

        boolean hasA = in.a1c().presentInWindow();
        boolean hasB = in.fbg().presentInWindow();
        boolean hasC = in.pbg().presentInWindow();
        boolean anyMetric = hasA || hasB || hasC;

        if (!anyMetric) {
            return finish(
                    Label.NONE,
                    group,
                    targets,
                    redHits,
                    yellowHits,
                    "联系患者，恢复数据采集",
                    "RESTORE_DATA",
                    in,
                    Map.of());
        }

        if (in.hypo14dCount() >= 2 || in.hypoArchiveFallbackHit()) {
            redHits.add(in.hypo14dCount() >= 2 ? "HYPO_14D" : "HYPO_ARCHIVE_FALLBACK");
        }
        if (hasA && ge(in.a1c().value(), bd("9.0"))) {
            redHits.add("A1C_GE_9");
        }
        if (hasB && hasC && ge(in.fbg().value(), bd("10.0")) && ge(in.pbg().value(), bd("13.9"))) {
            redHits.add("FBG_PBG_BOTH_HIGH");
        }
        if (!redHits.isEmpty()) {
            return finish(
                    Label.RED,
                    group,
                    targets,
                    redHits,
                    yellowHits,
                    "立即就医，紧急干预",
                    "URGENT_CARE",
                    in,
                    Map.of());
        }

        if (hasA && ge(in.a1c().value(), targets.a1c())) {
            yellowHits.add("A1C_OVER_TARGET");
        }
        if (hasB && ge(in.fbg().value(), targets.fbg())) {
            yellowHits.add("FBG_OVER_TARGET");
        }
        if (hasC && ge(in.pbg().value(), targets.pbg())) {
            yellowHits.add("PBG_OVER_TARGET");
        }
        if (!yellowHits.isEmpty()) {
            return finish(
                    Label.YELLOW,
                    group,
                    targets,
                    redHits,
                    yellowHits,
                    "调整治疗方案，加强监测",
                    "ADJUST_THERAPY",
                    in,
                    Map.of());
        }

        boolean greenOk = false;
        if (hasA || (hasB && hasC)) {
            boolean aOk = !hasA || lt(in.a1c().value(), targets.a1c());
            boolean bcOk = !(hasB && hasC)
                    || (lt(in.fbg().value(), targets.fbg()) && lt(in.pbg().value(), targets.pbg()));
            // 有 A：A 必须达标；有 BC：B 与 C 均达标；两者皆有则同时满足
            greenOk = aOk && bcOk && (hasA || (hasB && hasC));
        }

        if (greenOk) {
            return finish(
                    Label.GREEN,
                    group,
                    targets,
                    redHits,
                    yellowHits,
                    "维持当前管理，定期复查",
                    "MAINTAIN",
                    in,
                    Map.of());
        }

        boolean nearGreen = !hasA
                && ((hasB && !hasC && lt(in.fbg().value(), targets.fbg()))
                        || (hasC && !hasB && lt(in.pbg().value(), targets.pbg())));
        if (nearGreen) {
            return finish(
                    Label.NEAR_GREEN,
                    group,
                    targets,
                    redHits,
                    yellowHits,
                    "补充缺失数据，确认达标状态",
                    "SUPPLEMENT_DATA",
                    in,
                    Map.of());
        }

        return finish(
                Label.NONE,
                group,
                targets,
                redHits,
                yellowHits,
                "联系患者，恢复数据采集",
                "RESTORE_DATA",
                in,
                Map.of("incompleteCombo", true));
    }

    private static Output finish(
            Label label,
            TargetGroup group,
            Targets targets,
            List<String> redHits,
            List<String> yellowHits,
            String advice,
            String adviceCode,
            Input in,
            Map<String, Object> more) {
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("targetGroup", group.name());
        extras.put(
                "targets",
                Map.of(
                        "a1c", targets.a1c(),
                        "fbg", targets.fbg(),
                        "pbg", targets.pbg()));
        extras.put(
                "metrics",
                Map.of(
                        "a1c", metricMap(in.a1c()),
                        "fbg", metricMap(in.fbg()),
                        "pbg", metricMap(in.pbg())));
        extras.put("hypo14dCount", in.hypo14dCount());
        extras.put("hypoArchiveFallbackHit", in.hypoArchiveFallbackHit());
        extras.put("comorbidityCount", in.comorbidityCount());
        extras.put("endStage", in.endStageChronic());
        extras.put("redHits", List.copyOf(redHits));
        extras.put("yellowHits", List.copyOf(yellowHits));
        extras.put("adviceCode", adviceCode);
        extras.putAll(more);
        return new Output(label, group, targets, List.copyOf(redHits), List.copyOf(yellowHits), advice, adviceCode, extras);
    }

    private static Map<String, Object> metricMap(Metric m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("value", m.value());
        map.put("presentInWindow", m.presentInWindow());
        return map;
    }

    private static boolean ge(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) >= 0;
    }

    private static boolean lt(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) < 0;
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }
}
