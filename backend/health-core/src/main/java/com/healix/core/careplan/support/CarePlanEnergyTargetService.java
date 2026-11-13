package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.diet.domain.DietGoal;
import com.healix.core.diet.dto.DietGoalDto;
import com.healix.core.diet.enums.DietGoalTypeEnum;
import com.healix.core.diet.service.DietGoalService;
import com.healix.core.diet.service.DietGoalService.GoalRequest;
import com.healix.core.diet.support.DietContextLoader;
import com.healix.core.diet.support.EnergyTargetCalculator;
import com.healix.core.diet.support.EnergyTargetCalculator.MacroRatio;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 方案饮食块的热量目标 {@code diet.energyTarget}。
 *
 * <p>制定方案时即可落热量：已有饮食目标则引用；尚无目标时按档案自动估算（肥胖/超重偏减重）。
 * 健管师可在方案内调整（{@code PLAN_EDIT}）；发布时默认同步为医护设定的饮食目标，不必先去饮食记录页设定。
 *
 * <p>生成草稿时（{@link #applyFresh}）若方案目标明确是减重，但档案饮食目标仍是维持/基准体重过期，
 * 会按当前体征重算带缺口的减重热量，避免 summary/calorieHint 写「减重」却落维持热量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarePlanEnergyTargetService {

    public static final String FIELD = "energyTarget";
    public static final String SOURCE_PATIENT_GOAL = "PATIENT_GOAL";
    public static final String SOURCE_STAFF_GOAL = "STAFF_GOAL";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";
    public static final String SOURCE_PLAN_EDIT = "PLAN_EDIT";

    private static final String TEMPLATE_HINT = "按身高体重与活动量个体化，避免极端节食";
    private static final double DEFAULT_LOSE_RATE = 0.5;
    /** 中国成人超重 BMI 阈值，达到则估算默认偏减重 */
    private static final double OVERWEIGHT_BMI = 24.0;
    /** 饮食目标基准体重与当前体重偏差超过此值视为过期，需按现体征重算 */
    private static final double STALE_WEIGHT_KG = 2.0;
    private static final Pattern KCAL_IN_TEXT =
            Pattern.compile("(\\d{3,4})\\s*(千卡|大卡|kcal)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY_RATE =
            Pattern.compile("每周\\s*(?:约|大约)?\\s*(\\d+(?:\\.\\d+)?)\\s*kg", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKS_LOSE = Pattern.compile(
            "(\\d+)\\s*周[^。；;]{0,24}?(?:减重|减约|减轻|降至|降到|减)\\s*(?:约)?\\s*(\\d+(?:\\.\\d+)?)\\s*kg",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTHS_LOSE = Pattern.compile(
            "(\\d+)\\s*个?月[^。；;]{0,24}?(?:减重|减约|减轻|降至|降到|减)\\s*(?:约)?\\s*(\\d+(?:\\.\\d+)?)\\s*kg",
            Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> MISSING_LABELS =
            Map.of("GENDER", "性别", "BIRTHDAY", "出生日期", "HEIGHT", "身高", "WEIGHT", "体重");

    private final DietGoalService goalService;
    private final DietContextLoader dietContextLoader;

    /** 当前热量目标；无饮食目标时按档案自动估算；档案不全无法计算时返回 null。 */
    public ObjectNode current(String tenantId, String peopleId) {
        DietGoal goal = goalService.current(tenantId, peopleId);
        if (goal != null && goal.getEnergyKcal() != null) {
            String source =
                    DietGoalService.SOURCE_STAFF.equals(goal.getSource()) ? SOURCE_STAFF_GOAL : SOURCE_PATIENT_GOAL;
            return node(
                    goal.getEnergyKcal(),
                    goal.getCarbG(),
                    goal.getProteinG(),
                    goal.getFatG(),
                    goal.getGoalType(),
                    goal.getRateKgPerWeek(),
                    source);
        }
        return estimateForPlan(tenantId, peopleId, null);
    }

    /**
     * 尚无饮食目标时，按病种/BMI 推断减重或维持，用公式估算写入方案（source=ESTIMATED）。
     */
    private ObjectNode estimateForPlan(String tenantId, String peopleId, LoseIntent intent) {
        try {
            var status = goalService.status(tenantId, peopleId, false);
            DietGoalTypeEnum type = DietGoalTypeEnum.MAINTAIN;
            Double rate = null;
            CarePlanTemplateKeyEnum disease = dietContextLoader.load(tenantId, peopleId).diseaseKey();
            boolean obesityDisease = disease != null && disease.canonical() == CarePlanTemplateKeyEnum.OBESITY;
            Double bmi = null;
            if (status.heightCm() != null && status.weightKg() != null && status.heightCm() > 0) {
                double m = status.heightCm() / 100.0;
                bmi = status.weightKg() / (m * m);
            }
            boolean lose = (intent != null && intent.lose())
                    || obesityDisease
                    || (bmi != null && bmi >= OVERWEIGHT_BMI);
            if (lose) {
                type = DietGoalTypeEnum.LOSE;
                rate = intent != null && intent.rateKgPerWeek() != null
                        ? intent.rateKgPerWeek()
                        : DEFAULT_LOSE_RATE;
            }
            DietGoalDto est = goalService.preview(
                    tenantId,
                    peopleId,
                    new GoalRequest(type.name(), rate, null, null, null, null, null),
                    false);
            if (est != null && est.getEnergyKcal() != null) {
                return node(
                        est.getEnergyKcal(),
                        est.getCarbG(),
                        est.getProteinG(),
                        est.getFatG(),
                        est.getGoalType(),
                        est.getRateKgPerWeek(),
                        SOURCE_ESTIMATED);
            }
        } catch (BusinessException e) {
            log.debug("[CarePlan] energy target not computable peopleId={} reason={}", peopleId, e.getMessage());
        }
        return null;
    }

    /** 写入方案上下文供模型参考：有目标给数字，没有则给缺失的档案项。 */
    public ObjectNode promptContext(String tenantId, String peopleId) {
        return promptContext(tenantId, peopleId, null);
    }

    /**
     * 写入方案上下文供模型参考。传入已含 {@code goals}/{@code latestMetrics} 的快照时，
     * 按减重意图选用带缺口的热量，避免把过期「维持」目标喂给模型。
     */
    public ObjectNode promptContext(String tenantId, String peopleId, ObjectNode contextSnapshot) {
        try {
            ObjectNode target = resolveForGeneration(tenantId, peopleId, contextSnapshot);
            if (target != null) {
                target.put("available", true);
                return target;
            }
            ObjectNode n = JsonUtils.emptyObject();
            n.put("available", false);
            ArrayNode missing = n.putArray("missing");
            goalService.status(tenantId, peopleId, false).missing()
                    .forEach(m -> missing.add(MISSING_LABELS.getOrDefault(m, m)));
            return n;
        } catch (RuntimeException e) {
            log.warn("[CarePlan] energy target context failed peopleId={}: {}", peopleId, e.getMessage());
            return null;
        }
    }

    /** 新生成的方案：丢弃模型自带的热量目标，按饮食目标或档案估算写入，并校正文案中的热量数字。 */
    public void applyFresh(String tenantId, String peopleId, ObjectNode diet) {
        applyFresh(tenantId, peopleId, diet, null);
    }

    /**
     * 新生成的方案：结合 {@code contextSnapshot}（goals / latestMetrics / summary）判定减重意图，
     * 避免沿用过期的「维持」饮食目标导致热量无缺口。
     */
    public void applyFresh(String tenantId, String peopleId, ObjectNode diet, ObjectNode contextSnapshot) {
        if (diet == null) {
            return;
        }
        diet.remove(FIELD);
        ObjectNode target = resolveForGeneration(tenantId, peopleId, contextSnapshot);
        if (target == null) {
            diet.remove(FIELD);
        } else {
            diet.set(FIELD, target);
        }
        // 生成后强制用服务端数字重写文案，避免模型自算或沿用旧维持热量
        rewriteHint(diet, target);
        alignSummaryKcal(contextSnapshot, target);
        if (contextSnapshot != null && target != null) {
            ObjectNode snapTarget = target.deepCopy();
            snapTarget.put("available", true);
            contextSnapshot.set(FIELD, snapTarget);
        }
    }

    /** 编辑 / 修订后：保留健管师手改的热量（重算三大营养素），其余来源刷新为最新饮食目标或估算。 */
    public void apply(String tenantId, String peopleId, ObjectNode diet) {
        if (diet == null) {
            return;
        }
        JsonNode existing = diet.get(FIELD);
        ObjectNode target;
        if (existing != null && SOURCE_PLAN_EDIT.equals(existing.path("source").asText())) {
            target = planEdit(tenantId, peopleId, existing);
        } else {
            target = current(tenantId, peopleId);
        }
        if (target == null) {
            diet.remove(FIELD);
        } else {
            diet.set(FIELD, target);
        }
        fixHint(diet, target);
    }

    /**
     * 发布：方案内手改或自动估算的热量，默认同步为医护设定的饮食目标（可勾选取消）。
     *
     * @param sync null 表示前端未传，按默认处理（默认同步）
     */
    public void onPublish(String tenantId, String peopleId, String staffId, ObjectNode diet, Boolean sync) {
        apply(tenantId, peopleId, diet);
        JsonNode target = diet == null ? null : diet.get(FIELD);
        if (target == null) {
            return;
        }
        String source = target.path("source").asText();
        boolean fromPlan = SOURCE_PLAN_EDIT.equals(source) || SOURCE_ESTIMATED.equals(source);
        boolean doSync = fromPlan && !Boolean.FALSE.equals(sync);
        if (!doSync) {
            return;
        }
        DietGoal goal = goalService.current(tenantId, peopleId);
        String goalType = target.path("goalType").asText(null);
        Double rate = target.hasNonNull("rateKgPerWeek") ? target.get("rateKgPerWeek").asDouble() : null;
        DietGoalDto saved = goalService.saveByStaff(tenantId, peopleId, staffId, new GoalRequest(
                StringUtils.hasText(goalType) ? goalType : (goal == null ? null : goal.getGoalType()),
                rate != null ? rate : (goal == null ? null : goal.getRateKgPerWeek()),
                goal == null ? null : goal.getActivityLevel(),
                null,
                null,
                target.path("energyKcal").asInt(),
                "随管理方案发布"));
        ObjectNode synced = node(
                saved.getEnergyKcal(),
                saved.getCarbG(),
                saved.getProteinG(),
                saved.getFatG(),
                saved.getGoalType(),
                saved.getRateKgPerWeek(),
                SOURCE_STAFF_GOAL);
        diet.set(FIELD, synced);
        rewriteHint(diet, synced);
    }

    /**
     * 生成方案时选用热量：现有饮食目标若与减重意图冲突、或基准体重相对当前体征过期，则按现体征重算。
     */
    private ObjectNode resolveForGeneration(String tenantId, String peopleId, ObjectNode contextSnapshot) {
        LoseIntent intent = inferLoseIntent(tenantId, peopleId, contextSnapshot);
        DietGoal goal = goalService.current(tenantId, peopleId);
        Double currentWeight = currentWeightKg(tenantId, peopleId, contextSnapshot);

        if (goal != null && goal.getEnergyKcal() != null) {
            boolean staleWeight = currentWeight != null
                    && goal.getBasisWeightKg() != null
                    && Math.abs(goal.getBasisWeightKg() - currentWeight) >= STALE_WEIGHT_KG;
            boolean wrongTypeForLose = intent.lose() && !DietGoalTypeEnum.LOSE.name().equals(goal.getGoalType());
            boolean maintainWithLoseCopy = DietGoalTypeEnum.MAINTAIN.name().equals(goal.getGoalType()) && intent.lose();

            if (!staleWeight && !wrongTypeForLose && !maintainWithLoseCopy) {
                String source = DietGoalService.SOURCE_STAFF.equals(goal.getSource())
                        ? SOURCE_STAFF_GOAL
                        : SOURCE_PATIENT_GOAL;
                return node(
                        goal.getEnergyKcal(),
                        goal.getCarbG(),
                        goal.getProteinG(),
                        goal.getFatG(),
                        goal.getGoalType(),
                        goal.getRateKgPerWeek(),
                        source);
            }

            // 已有减重目标但体征过期：沿用速度，按现体重重算缺口热量
            if (DietGoalTypeEnum.LOSE.name().equals(goal.getGoalType())) {
                Double rate = goal.getRateKgPerWeek() != null ? goal.getRateKgPerWeek() : DEFAULT_LOSE_RATE;
                ObjectNode recalculated = estimateForPlan(tenantId, peopleId, new LoseIntent(true, rate));
                if (recalculated != null) {
                    log.info(
                            "[CarePlan] refresh stale lose energy peopleId={} oldBasisKg={} currentKg={} rate={}",
                            peopleId,
                            goal.getBasisWeightKg(),
                            currentWeight,
                            rate);
                    return recalculated;
                }
            }
        }

        if (intent.lose()) {
            ObjectNode estimated = estimateForPlan(tenantId, peopleId, intent);
            if (estimated != null) {
                log.info(
                        "[CarePlan] estimate lose energy for plan peopleId={} rate={} reason=goal-or-bmi",
                        peopleId,
                        intent.rateKgPerWeek());
                return estimated;
            }
        }
        return estimateForPlan(tenantId, peopleId, null);
    }

    private record LoseIntent(boolean lose, Double rateKgPerWeek) {}

    private LoseIntent inferLoseIntent(String tenantId, String peopleId, ObjectNode contextSnapshot) {
        Double rate = null;
        boolean lose = false;
        if (contextSnapshot != null) {
            JsonNode goals = contextSnapshot.path("goals");
            String mode = goals.path("mode").asText("");
            String phase = goals.path("phase").asText("");
            if ("LOSS".equalsIgnoreCase(mode) || "LOSS".equalsIgnoreCase(phase) || "LOSE".equalsIgnoreCase(mode)) {
                lose = true;
            }
            JsonNode tracks = goals.path("careTracks");
            if (tracks.isArray()) {
                for (JsonNode t : tracks) {
                    if ("WEIGHT_LOSS".equalsIgnoreCase(t.asText()) || "OBESITY".equalsIgnoreCase(t.asText())) {
                        lose = true;
                    }
                }
            }
            String blob = String.join(
                    " ",
                    contextSnapshot.path("summary").asText(""),
                    goals.path("modeLabel").asText(""),
                    goals.path("primaryAppealLabel").asText(""));
            rate = firstNonNull(parseWeeklyRate(blob), parseRateFromPeriod(blob));
            if (blob.contains("减重") || blob.contains("降体重") || blob.contains("减轻体重")) {
                lose = true;
            }
        }

        try {
            var status = goalService.status(tenantId, peopleId, false);
            if (status.heightCm() != null && status.weightKg() != null && status.heightCm() > 0) {
                double bmi = status.weightKg() / Math.pow(status.heightCm() / 100.0, 2);
                if (bmi >= OVERWEIGHT_BMI) {
                    lose = true;
                }
            }
            CarePlanTemplateKeyEnum disease = dietContextLoader.load(tenantId, peopleId).diseaseKey();
            if (disease != null && disease.canonical() == CarePlanTemplateKeyEnum.OBESITY) {
                lose = true;
            }
        } catch (RuntimeException ignored) {
            // 档案不全时不阻断；仅用上下文意图
        }

        if (lose && rate == null) {
            rate = DEFAULT_LOSE_RATE;
        }
        return new LoseIntent(lose, rate);
    }

    private static Double parseWeeklyRate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher m = WEEKLY_RATE.matcher(text);
        if (!m.find()) {
            return null;
        }
        double v = Double.parseDouble(m.group(1));
        return v >= 0.1 && v <= 1.0 ? v : null;
    }

    private static Double parseRateFromPeriod(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher weeks = WEEKS_LOSE.matcher(text);
        if (weeks.find()) {
            double w = Double.parseDouble(weeks.group(1));
            double kg = Double.parseDouble(weeks.group(2));
            if (w > 0 && kg > 0) {
                double rate = Math.round((kg / w) * 10.0) / 10.0;
                if (rate >= 0.1 && rate <= 1.0) {
                    return rate;
                }
            }
        }
        Matcher months = MONTHS_LOSE.matcher(text);
        if (months.find()) {
            double mo = Double.parseDouble(months.group(1));
            double kg = Double.parseDouble(months.group(2));
            if (mo > 0 && kg > 0) {
                double rate = Math.round((kg / (mo * 4.3)) * 10.0) / 10.0;
                if (rate >= 0.1 && rate <= 1.0) {
                    return rate;
                }
            }
        }
        return null;
    }

    private Double currentWeightKg(String tenantId, String peopleId, ObjectNode contextSnapshot) {
        if (contextSnapshot != null) {
            JsonNode metrics = contextSnapshot.path("latestMetrics");
            if (metrics.isArray()) {
                for (JsonNode m : metrics) {
                    if ("WEIGHT".equalsIgnoreCase(m.path("metricType").asText())) {
                        try {
                            return Double.parseDouble(m.path("value").asText());
                        } catch (NumberFormatException ignored) {
                            // fall through
                        }
                    }
                }
            }
        }
        try {
            return goalService.status(tenantId, peopleId, false).weightKg();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Double firstNonNull(Double a, Double b) {
        return a != null ? a : b;
    }

    private ObjectNode planEdit(String tenantId, String peopleId, JsonNode existing) {
        int kcal = existing.path("energyKcal").asInt(0);
        if (kcal < 800 || kcal > 5000) {
            throw new BusinessException(400, "方案每日热量需在 800–5000 kcal 之间");
        }
        MacroRatio ratio = EnergyTargetCalculator.macroRatio(dietContextLoader.load(tenantId, peopleId).diseaseKey());
        String goalType = existing.path("goalType").asText(null);
        Double rate = existing.hasNonNull("rateKgPerWeek") ? existing.get("rateKgPerWeek").asDouble() : null;
        return node(
                kcal,
                (int) Math.round(kcal * ratio.carb() / 4),
                (int) Math.round(kcal * ratio.protein() / 4),
                (int) Math.round(kcal * ratio.fat() / 9),
                goalType,
                rate,
                SOURCE_PLAN_EDIT);
    }

    /** 热量文案为空、是模板默认句，或写了与目标不符的热量数字时，按目标重写。 */
    private static void fixHint(ObjectNode diet, ObjectNode target) {
        String hint = diet.path("calorieHint").asText("").trim();
        boolean placeholder = hint.isEmpty() || TEMPLATE_HINT.equals(hint);
        if (target == null) {
            if (placeholder || !kcalNumbers(hint).isEmpty()) {
                diet.put("calorieHint", "完善性别、出生日期、身高、体重后可计算每日热量目标；避免极端节食");
            }
            return;
        }
        int kcal = target.path("energyKcal").asInt();
        List<Integer> nums = kcalNumbers(hint);
        boolean mismatched = !nums.isEmpty() && nums.stream().noneMatch(n -> Math.abs(n - kcal) <= 50);
        boolean loseCopyOnMaintain = DietGoalTypeEnum.MAINTAIN
                        .name()
                        .equals(target.path("goalType").asText())
                && (hint.contains("减重") || hint.contains("每周约减") || hint.contains("每周减"));
        if (placeholder || mismatched || loseCopyOnMaintain) {
            diet.put("calorieHint", defaultHint(target));
        }
    }

    /** 生成场景强制用目标重写 calorieHint，保证与 energyTarget 完全一致。 */
    private static void rewriteHint(ObjectNode diet, ObjectNode target) {
        if (target == null) {
            diet.put("calorieHint", "完善性别、出生日期、身高、体重后可计算每日热量目标；避免极端节食");
            return;
        }
        diet.put("calorieHint", defaultHint(target));
    }

    /** 校正方案总结中的热量数字，使其与 energyTarget.energyKcal 一致。 */
    static void alignSummaryKcal(ObjectNode contextSnapshot, ObjectNode target) {
        if (contextSnapshot == null || target == null || !target.hasNonNull("energyKcal")) {
            return;
        }
        int kcal = target.path("energyKcal").asInt();
        String summary = contextSnapshot.path("summary").asText(null);
        if (!StringUtils.hasText(summary)) {
            return;
        }
        List<Integer> nums = kcalNumbers(summary);
        if (nums.isEmpty()) {
            return;
        }
        if (nums.stream().allMatch(n -> Math.abs(n - kcal) <= 50)) {
            return;
        }
        Matcher m = KCAL_IN_TEXT.matcher(summary);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(kcal + " " + m.group(2)));
        }
        m.appendTail(sb);
        contextSnapshot.put("summary", sb.toString());
    }

    private static List<Integer> kcalNumbers(String text) {
        Matcher m = KCAL_IN_TEXT.matcher(text);
        List<Integer> out = new ArrayList<>();
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }

    private static String defaultHint(JsonNode t) {
        StringBuilder sb = new StringBuilder(String.format(
                Locale.ROOT,
                "每日约 %d 千卡（碳水 %dg / 蛋白质 %dg / 脂肪 %dg）",
                t.path("energyKcal").asInt(),
                t.path("carbG").asInt(),
                t.path("proteinG").asInt(),
                t.path("fatG").asInt()));
        DietGoalTypeEnum type = parseGoalType(t.path("goalType").asText(null));
        double rate = t.path("rateKgPerWeek").asDouble(0);
        if (type == DietGoalTypeEnum.LOSE && rate > 0) {
            sb.append(String.format(Locale.ROOT, "，按每周减重 %.1f kg", rate));
        } else if (type == DietGoalTypeEnum.GAIN && rate > 0) {
            sb.append(String.format(Locale.ROOT, "，按每周增重 %.1f kg", rate));
        } else {
            sb.append("，以维持体重为目标");
        }
        if (SOURCE_ESTIMATED.equals(t.path("source").asText())) {
            sb.append("（制定方案时按档案自动估算）");
        }
        return sb.append("；避免极端节食").toString();
    }

    private static DietGoalTypeEnum parseGoalType(String v) {
        try {
            return DietGoalTypeEnum.parse(v);
        } catch (BusinessException e) {
            return DietGoalTypeEnum.MAINTAIN;
        }
    }

    private static ObjectNode node(
            Integer kcal, Integer carb, Integer protein, Integer fat, String goalType, Double rate, String source) {
        ObjectNode n = JsonUtils.emptyObject();
        n.put("energyKcal", kcal);
        n.put("carbG", carb);
        n.put("proteinG", protein);
        n.put("fatG", fat);
        n.put("goalType", StringUtils.hasText(goalType) ? goalType : DietGoalTypeEnum.MAINTAIN.name());
        if (rate != null && rate > 0) {
            n.put("rateKgPerWeek", rate);
        }
        n.put("source", source);
        return n;
    }
}
