package com.healix.core.report.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 由报告快照推导「概览」：关键数字（含较上期）、要点卡（做得好 / 需注意 / 下期重点）、里程碑、目标进度。
 *
 * <p>纯规则、面向患者口吻；AI 寄语生成时可在 narrative.highlights 里改写要点卡。
 */
@Component
public class HealthReportOverviewBuilder {

    public static final String GOOD = "GOOD";
    public static final String WATCH = "WATCH";
    public static final String FOCUS = "FOCUS";

    private static final int MAX_PER_TYPE = 3;
    private static final double PLAN_TARGET = 0.8;
    private static final double METRIC_TARGET = 0.8;
    private static final int DEFAULT_WEEKLY_ACTIVE_MIN = 150;

    public void apply(Map<String, Object> root, String periodType) {
        Ctx c = new Ctx(root);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("kpis", kpis(c));
        overview.put("highlights", highlights(c));
        root.put("overview", overview);
        root.put("milestones", milestones(c));
        root.put("goals", goals(c));
    }

    // ---------------------------------------------------------------- KPI

    private List<Map<String, Object>> kpis(Ctx c) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (c.planDue() > 0) {
            out.add(kpi("plan", "方案完成率", pct(c.planRate()), pct(c.prevPlanRate()), "%", true, 3,
                    "完成 " + c.planDone() + "/" + c.planDue() + " 次"));
        }
        if (c.medDueDays() > 0) {
            out.add(kpi("med", "用药达标率", pct(c.medRate()), pct(c.prevMedRate()), "%", true, 3,
                    "按时服药 " + c.medOkDays() + "/" + c.medDueDays() + " 天"));
        }
        Map<String, Object> primary = c.primaryMetric();
        if (primary != null) {
            Map<String, Object> prev = map(primary.get("prev"));
            out.add(kpi(
                    "metric",
                    str(primary.get("label")) + "达标率",
                    pct(dbl(primary.get("inRangeRate"))),
                    pct(dbl(prev.get("inRangeRate"))),
                    "%",
                    true,
                    5,
                    "测量 " + intv(primary.get("count")) + " 次 · 异常 " + intv(primary.get("abnormalCount")) + " 次"));
        }
        Map<String, Object> ex = c.exercise();
        Integer target = intOrNull(ex.get("weeklyActiveMinTarget"));
        if (intv(ex.get("activeDays")) > 0 || target != null) {
            Map<String, Object> prev = map(ex.get("prev"));
            out.add(kpi("exercise", "周均活动", dbl(ex.get("avgWeeklyActiveMin")), dbl(prev.get("avgWeeklyActiveMin")),
                    "分钟", true, 10,
                    "目标 " + (target != null ? target : DEFAULT_WEEKLY_ACTIVE_MIN) + " 分钟/周"));
        }
        Map<String, Object> diet = c.diet();
        Map<String, Object> dietPrev = map(diet.get("prev"));
        if (diet.get("targetKcal") != null && intv(diet.get("completeDays")) > 0) {
            out.add(kpi("diet", "热量达标", dbl(diet.get("onTargetDays")), dbl(dietPrev.get("onTargetDays")),
                    "天", true, 1,
                    "记全三餐 " + intv(diet.get("completeDays")) + " 天 · 日均 " + intv(diet.get("avgKcal")) + " kcal"));
        } else if (intv(diet.get("recordDays")) > 0) {
            out.add(kpi("diet", "饮食记录", dbl(diet.get("recordDays")), dbl(dietPrev.get("recordDays")),
                    "天", true, 1, "共 " + c.dayCount() + " 天"));
        }
        Map<String, Object> weight = c.metric("WEIGHT");
        if (weight != null && intv(weight.get("count")) >= 2) {
            Map<String, Object> stats = map(weight.get("stats"));
            Map<String, Object> k = kpi("weight", "体重变化", dbl(stats.get("delta")), null, "kg", null, 0,
                    fmt(dbl(stats.get("first"))) + " → " + fmt(dbl(stats.get("last"))) + " kg");
            k.put("display", signed(dbl(stats.get("delta"))));
            out.add(k);
        }
        return out;
    }

    /**
     * @param higherBetter null 表示不评价好坏
     * @param flatWithin 变化在此范围内视为持平
     */
    private static Map<String, Object> kpi(
            String key,
            String label,
            Double value,
            Double prev,
            String unit,
            Boolean higherBetter,
            double flatWithin,
            String sub) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("unit", unit);
        m.put("display", value == null ? "—" : fmt(value));
        m.put("sub", sub);
        m.put("prev", prev);
        Double delta = value != null && prev != null ? round1(value - prev) : null;
        m.put("delta", delta);
        String trend = null;
        if (delta != null && higherBetter != null) {
            if (Math.abs(delta) < flatWithin) {
                trend = "FLAT";
            } else {
                trend = (delta > 0) == higherBetter ? "BETTER" : "WORSE";
            }
        }
        m.put("trend", trend);
        m.put("deltaText", delta == null ? null : Math.abs(delta) < flatWithin ? "与上期持平" : "较上期 " + signed(delta) + unit);
        return m;
    }

    // ---------------------------------------------------------------- 要点卡

    private List<Map<String, Object>> highlights(Ctx c) {
        List<String> good = new ArrayList<>();
        List<String> watch = new ArrayList<>();
        List<String> focus = new ArrayList<>();

        Double planRate = c.planRate();
        Double prevPlan = c.prevPlanRate();
        if (planRate != null && planRate >= 0.9) {
            good.add("方案任务完成率 " + pct0(planRate) + "%，执行很扎实");
        } else if (planRate != null && prevPlan != null && planRate - prevPlan >= 0.1) {
            good.add("方案完成率比上期提升了 " + Math.round((planRate - prevPlan) * 100) + " 个百分点");
        }
        int streak = intv(c.plan().get("longestStreak"));
        if (streak >= 5) {
            good.add("连续 " + streak + " 天完成了全部方案任务");
        }
        Double medRate = c.medRate();
        if (medRate != null && medRate >= 0.95 && c.medDueDays() >= 3) {
            good.add("按时服药 " + c.medOkDays() + "/" + c.medDueDays() + " 天，用药很规律");
        }
        for (Map<String, Object> m : c.metrics()) {
            String family = str(m.get("family"));
            if (!"BP".equals(family) && !"GLUCOSE".equals(family)) {
                continue;
            }
            int count = intv(m.get("count"));
            int abnormal = intv(m.get("abnormalCount"));
            Double rate = dbl(m.get("inRangeRate"));
            if (count >= 3 && rate != null && rate >= 0.9) {
                good.add(str(m.get("label")) + "测量 " + count + " 次，" + (count - abnormal) + " 次在参考范围内");
            }
            if ("BP".equals(family)) {
                Double sys = dbl(map(m.get("stats")).get("sysAvg"));
                Double prevSys = dbl(map(m.get("prev")).get("sysAvg"));
                if (sys != null && prevSys != null && prevSys - sys >= 5) {
                    good.add("平均收缩压较上期下降 " + Math.round(prevSys - sys) + " mmHg");
                }
            }
        }
        Map<String, Object> ex = c.exercise();
        int exTarget = intOrNull(ex.get("weeklyActiveMinTarget")) != null
                ? intv(ex.get("weeklyActiveMinTarget"))
                : DEFAULT_WEEKLY_ACTIVE_MIN;
        int avgWeekly = intv(ex.get("avgWeeklyActiveMin"));
        if (intv(ex.get("activeDays")) > 0 && avgWeekly >= exTarget) {
            good.add("周均活动 " + avgWeekly + " 分钟，达到了 " + exTarget + " 分钟的目标");
        }
        Map<String, Object> diet = c.diet();
        int recordDays = intv(diet.get("recordDays"));
        if (recordDays > 0 && recordDays >= c.dayCount() * 0.8) {
            good.add("坚持记录饮食 " + recordDays + "/" + c.dayCount() + " 天");
        }
        if (good.isEmpty() && c.planDone() > 0) {
            good.add("本期完成了 " + c.planDone() + " 次方案任务，一直在坚持");
        }

        // 需注意 + 对应下期重点
        boolean hasMetricData = false;
        for (Map<String, Object> m : c.metrics()) {
            String family = str(m.get("family"));
            if ("WEIGHT".equals(family)) {
                continue;
            }
            hasMetricData = true;
            int abnormal = intv(m.get("abnormalCount"));
            if (abnormal >= 2) {
                List<Map<String, Object>> items = list(m.get("abnormalItems"));
                String latest = items.isEmpty() ? "" : "，最近一次" + str(items.get(0).get("text"));
                watch.add(str(m.get("label")) + "有 " + abnormal + " 次超出参考范围" + latest);
                if ("BP".equals(family)) {
                    focus.add("坚持每天早晚各测一次血压，连续偏高请及时复测或就诊");
                } else if ("GLUCOSE".equals(family)) {
                    focus.add("按时监测空腹和餐后血糖，偏高时记下当餐吃了什么，方便我们分析");
                } else {
                    focus.add("留意" + str(m.get("label")) + "变化，持续异常请复测或就诊");
                }
            }
        }
        if (!hasMetricData) {
            watch.add("本期没有居家测量记录");
            focus.add("每周至少测量 3 次血压或血糖，并在小程序里记录");
        }
        Map<String, Object> worst = c.worstTask();
        if (planRate != null && planRate < 0.6) {
            watch.add("方案完成率 " + pct0(planRate) + "%，有不少任务没有打卡");
        }
        if (worst != null) {
            watch.add("「" + str(worst.get("title")) + "」只完成了 " + intv(worst.get("done")) + "/" + intv(worst.get("due")) + " 次");
            focus.add("把「" + str(worst.get("title")) + "」固定在每天同一时间完成，可以设个提醒");
        } else if (planRate != null && planRate < 0.6) {
            focus.add("先从最容易完成的任务开始，逐步恢复打卡节奏");
        }
        if (medRate != null && medRate < 0.8) {
            List<Map<String, Object>> missed = list(c.med().get("missedBySlot"));
            String slot = missed.isEmpty() ? null : str(missed.get(0).get("label"));
            String slotText = slot == null || "每日".equals(slot) ? "" : "，多在" + slot + "漏服";
            watch.add("有 " + (c.medDueDays() - c.medOkDays()) + " 天没有按时服完药" + slotText);
            focus.add((slot == null || "每日".equals(slot) ? "设置固定的服药提醒" : "在" + slot + "设置服药提醒")
                    + "，服药后及时打卡");
        }
        Integer sodium = intOrNull(diet.get("avgSodiumMg"));
        int sodiumLimit = intv(diet.get("sodiumLimitMg"));
        if (sodium != null && sodiumLimit > 0 && sodium > sodiumLimit) {
            watch.add("日均钠约 " + sodium + " mg，超过建议的 " + sodiumLimit + " mg");
            focus.add("少用咸菜、酱料和加工肉，每天盐控制在 5 克以内");
        }
        int red = intv(diet.get("redMeals"));
        List<Map<String, Object>> flags = list(diet.get("flagTop"));
        if (red > 0) {
            String reason = flags.isEmpty() ? "" : "，主要是「" + str(flags.get(0).get("label")) + "」";
            watch.add("有 " + red + " 餐需要调整" + reason);
            if (!flags.isEmpty()) {
                focus.add("参考饮食方案，减少「" + str(flags.get(0).get("label")) + "」的情况");
            }
        }
        if (intv(ex.get("redCount")) > 0) {
            watch.add("运动中出现了 " + intv(ex.get("redCount")) + " 次明显不适");
            focus.add("运动时如再出现胸闷、头晕等不适，请立即停止并联系我们");
        } else if (avgWeekly < exTarget * 0.6
                && (intOrNull(ex.get("weeklyActiveMinTarget")) != null || intv(ex.get("activeDays")) > 0)) {
            watch.add("周均活动 " + avgWeekly + " 分钟，离 " + exTarget + " 分钟的目标还有差距");
            focus.add("每周安排 " + exTarget + " 分钟中等强度活动，可以拆成每天 20–30 分钟");
        }
        if (focus.isEmpty()) {
            focus.add("保持现在的节奏，继续按方案打卡和记录");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        addAll(out, GOOD, good);
        addAll(out, WATCH, watch);
        addAll(out, FOCUS, focus);
        return out;
    }

    private static void addAll(List<Map<String, Object>> out, String type, List<String> texts) {
        texts.stream().distinct().limit(MAX_PER_TYPE).forEach(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("text", t);
            out.add(m);
        });
    }

    // ---------------------------------------------------------------- 里程碑

    private List<Map<String, Object>> milestones(Ctx c) {
        List<String> out = new ArrayList<>();
        int streak = intv(c.plan().get("longestStreak"));
        if (streak >= 7) {
            out.add("连续 " + streak + " 天完成全部方案任务");
        }
        if (c.planDue() >= 7 && c.planDone() >= c.planDue()) {
            out.add("全部 " + c.planDue() + " 次方案任务都已完成");
        }
        Double planRate = c.planRate();
        Double prevPlan = c.prevPlanRate();
        if (planRate != null && prevPlan != null && planRate - prevPlan >= 0.2) {
            out.add("方案完成率较上期提升 " + Math.round((planRate - prevPlan) * 100) + " 个百分点");
        }
        if (c.medDueDays() >= 7 && c.medOkDays() >= c.medDueDays()) {
            out.add(c.medDueDays() + " 天全部按时服药");
        }
        for (Map<String, Object> m : c.metrics()) {
            if ("WEIGHT".equals(str(m.get("family")))) {
                continue;
            }
            int count = intv(m.get("count"));
            if (count >= 5 && intv(m.get("abnormalCount")) == 0) {
                out.add(str(m.get("label")) + " " + count + " 次测量全部在参考范围内");
            }
        }
        Map<String, Object> ex = c.exercise();
        Integer metWeeks = intOrNull(ex.get("targetMetWeeks"));
        int weekCount = intv(ex.get("weekCount"));
        if (metWeeks != null && weekCount >= 2 && metWeeks >= weekCount) {
            out.add("每一周的活动量都达到了目标");
        }
        int recordDays = intv(c.diet().get("recordDays"));
        if (c.dayCount() >= 7 && recordDays >= c.dayCount()) {
            out.add("每天都记录了饮食");
        }
        Map<String, Object> weight = c.metric("WEIGHT");
        Map<String, Object> bmi = c.metric("BMI");
        if (weight != null && bmi != null) {
            Double delta = dbl(map(weight.get("stats")).get("delta"));
            Double latestBmi = dbl(map(bmi.get("latest")).get("value"));
            if (delta != null && delta <= -1 && latestBmi != null && latestBmi >= 23.9) {
                out.add("体重较期初下降 " + fmt(-delta) + " kg");
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        out.forEach(t -> rows.add(Map.of("text", t)));
        return rows;
    }

    // ---------------------------------------------------------------- 目标进度

    private List<Map<String, Object>> goals(Ctx c) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (c.planDue() > 0 && c.planRate() != null) {
            double r = c.planRate();
            out.add(goal("plan", "方案完成率", "≥" + pct0(PLAN_TARGET) + "%", pct0(r) + "%", r / PLAN_TARGET, r >= PLAN_TARGET));
        }
        if (c.medDueDays() > 0 && c.medRate() != null) {
            double r = c.medRate();
            out.add(goal("med", "按时服药", "每天按时", c.medOkDays() + "/" + c.medDueDays() + " 天", r, r >= 0.9));
        }
        Map<String, Object> primary = c.primaryMetric();
        if (primary != null && dbl(primary.get("inRangeRate")) != null) {
            double r = dbl(primary.get("inRangeRate"));
            out.add(goal("metric", str(primary.get("label")) + "控制", "≥" + pct0(METRIC_TARGET) + "% 在范围内",
                    pct0(r) + "%", r / METRIC_TARGET, r >= METRIC_TARGET));
        }
        Map<String, Object> ex = c.exercise();
        Integer target = intOrNull(ex.get("weeklyActiveMinTarget"));
        if (target != null && target > 0) {
            int avg = intv(ex.get("avgWeeklyActiveMin"));
            Integer metWeeks = intOrNull(ex.get("targetMetWeeks"));
            String actual = metWeeks != null
                    ? "周均 " + avg + " 分钟 · " + metWeeks + "/" + intv(ex.get("weekCount")) + " 周达标"
                    : "周均 " + avg + " 分钟";
            out.add(goal("exercise", "每周活动", target + " 分钟/周", actual, (double) avg / target, avg >= target));
        }
        Map<String, Object> diet = c.diet();
        Integer kcal = intOrNull(diet.get("targetKcal"));
        int complete = intv(diet.get("completeDays"));
        if (kcal != null && complete > 0) {
            int onTarget = intv(diet.get("onTargetDays"));
            double r = (double) onTarget / complete;
            out.add(goal("energy", "每日热量", kcal + " kcal（±10%）", "达标 " + onTarget + "/" + complete + " 天", r, r >= 0.7));
        }
        Integer sodium = intOrNull(diet.get("avgSodiumMg"));
        int limit = intv(diet.get("sodiumLimitMg"));
        if (sodium != null && limit > 0) {
            double r = sodium <= 0 ? 1 : Math.min(1d, (double) limit / sodium);
            out.add(goal("sodium", "钠摄入", "<" + limit + " mg/天", "日均 " + sodium + " mg", r, sodium <= limit));
        }
        return out;
    }

    private static Map<String, Object> goal(
            String key, String label, String targetText, String actualText, double progress, boolean met) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("targetText", targetText);
        m.put("actualText", actualText);
        m.put("progress", round2(Math.max(0, Math.min(1, progress))));
        m.put("met", met);
        return m;
    }

    // ---------------------------------------------------------------- 取数

    private record Ctx(Map<String, Object> root) {
        Map<String, Object> plan() {
            return map(map(root.get("adherence")).get("plan"));
        }

        Map<String, Object> med() {
            return map(map(root.get("adherence")).get("med"));
        }

        Map<String, Object> diet() {
            return map(map(root.get("lifestyle")).get("diet"));
        }

        Map<String, Object> exercise() {
            return map(map(root.get("lifestyle")).get("exercise"));
        }

        List<Map<String, Object>> metrics() {
            return list(root.get("metrics"));
        }

        Map<String, Object> metric(String family) {
            return metrics().stream().filter(m -> family.equals(m.get("family"))).findFirst().orElse(null);
        }

        /** 优先血压，其次血糖、心率。 */
        Map<String, Object> primaryMetric() {
            for (String f : List.of("BP", "GLUCOSE", "HR")) {
                Map<String, Object> m = metric(f);
                if (m != null && intv(m.get("count")) > 0) {
                    return m;
                }
            }
            return null;
        }

        /** 完成率最低且明显偏低的任务（byTask 已按完成率升序）。 */
        Map<String, Object> worstTask() {
            for (Map<String, Object> t : list(plan().get("byTask"))) {
                Double r = dbl(t.get("rate"));
                if (intv(t.get("due")) >= 3 && r != null && r < 0.5) {
                    return t;
                }
                break;
            }
            return null;
        }

        int dayCount() {
            int n = intv(map(root.get("period")).get("dayCount"));
            return n > 0 ? n : intv(map(root.get("lifestyle")).get("dayCount"));
        }

        int planDue() {
            return intv(plan().get("dueCount"));
        }

        int planDone() {
            return intv(plan().get("doneCount"));
        }

        Double planRate() {
            return dbl(plan().get("rate"));
        }

        Double prevPlanRate() {
            return dbl(map(plan().get("prev")).get("rate"));
        }

        int medDueDays() {
            return intv(med().get("dueDayCount"));
        }

        int medOkDays() {
            return intv(med().get("okDayCount"));
        }

        Double medRate() {
            return dbl(med().get("rate"));
        }

        Double prevMedRate() {
            return dbl(map(med().get("prev")).get("rate"));
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object o) {
        if (!(o instanceof List<?> l)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object x : l) {
            if (x instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static Double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    static int intv(Object o) {
        return o instanceof Number n ? (int) Math.round(n.doubleValue()) : 0;
    }

    static Integer intOrNull(Object o) {
        return o instanceof Number n ? (int) Math.round(n.doubleValue()) : null;
    }

    private static Double pct(Double rate) {
        return rate == null ? null : (double) Math.round(rate * 100);
    }

    private static long pct0(double rate) {
        return Math.round(rate * 100);
    }

    private static double round1(double v) {
        return Math.round(v * 10d) / 10d;
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }

    private static String fmt(Double v) {
        if (v == null) {
            return "—";
        }
        return v == Math.rint(v) ? String.valueOf(v.longValue()) : String.valueOf(round1(v));
    }

    private static String signed(Double v) {
        if (v == null) {
            return "—";
        }
        return (v > 0 ? "+" : v < 0 ? "-" : "") + fmt(Math.abs(v));
    }
}
