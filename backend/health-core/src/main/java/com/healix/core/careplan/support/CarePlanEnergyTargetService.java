package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 方案饮食块的热量目标 {@code diet.energyTarget}：以饮食记录的热量目标为唯一来源，方案只引用不另算。
 *
 * <p>来源：PATIENT_GOAL / STAFF_GOAL 取自当前饮食目标；ESTIMATED 为尚无目标时按维持体重估算；
 * PLAN_EDIT 为健管师在方案里手改的热量，发布时可同步为医护设定的饮食目标。
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
    private static final Pattern KCAL_IN_TEXT = Pattern.compile("(\\d{3,4})\\s*(千卡|大卡|kcal)", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> MISSING_LABELS =
            Map.of("GENDER", "性别", "BIRTHDAY", "出生日期", "HEIGHT", "身高", "WEIGHT", "体重");

    private final DietGoalService goalService;
    private final DietContextLoader dietContextLoader;

    /** 当前热量目标；无目标时按维持体重估算；档案不全无法计算时返回 null。 */
    public ObjectNode current(String tenantId, String peopleId) {
        DietGoal goal = goalService.current(tenantId, peopleId);
        if (goal != null && goal.getEnergyKcal() != null) {
            String source = DietGoalService.SOURCE_STAFF.equals(goal.getSource()) ? SOURCE_STAFF_GOAL : SOURCE_PATIENT_GOAL;
            return node(goal.getEnergyKcal(), goal.getCarbG(), goal.getProteinG(), goal.getFatG(),
                    goal.getGoalType(), goal.getRateKgPerWeek(), source);
        }
        try {
            DietGoalDto est = goalService.preview(
                    tenantId, peopleId, new GoalRequest(null, null, null, null, null, null, null), false);
            if (est != null && est.getEnergyKcal() != null) {
                return node(est.getEnergyKcal(), est.getCarbG(), est.getProteinG(), est.getFatG(),
                        est.getGoalType(), est.getRateKgPerWeek(), SOURCE_ESTIMATED);
            }
        } catch (BusinessException e) {
            log.debug("[CarePlan] energy target not computable peopleId={} reason={}", peopleId, e.getMessage());
        }
        return null;
    }

    /** 写入方案上下文供模型参考：有目标给数字，没有则给缺失的档案项。 */
    public ObjectNode promptContext(String tenantId, String peopleId) {
        try {
            ObjectNode target = current(tenantId, peopleId);
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

    /** 新生成的方案：丢弃模型自带的热量目标，按饮食目标重写。 */
    public void applyFresh(String tenantId, String peopleId, ObjectNode diet) {
        if (diet == null) {
            return;
        }
        diet.remove(FIELD);
        apply(tenantId, peopleId, diet);
    }

    /** 编辑 / 修订后：保留健管师手改的热量（重算三大营养素），其余来源刷新为最新的饮食目标。 */
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
     * 发布：手改的热量默认同步为医护设定的饮食目标；估算值仅在显式勾选时同步。
     *
     * @param sync null 表示前端未传，按默认处理
     */
    public void onPublish(String tenantId, String peopleId, String staffId, ObjectNode diet, Boolean sync) {
        apply(tenantId, peopleId, diet);
        JsonNode target = diet == null ? null : diet.get(FIELD);
        if (target == null) {
            return;
        }
        String source = target.path("source").asText();
        boolean doSync = SOURCE_PLAN_EDIT.equals(source)
                ? !Boolean.FALSE.equals(sync)
                : SOURCE_ESTIMATED.equals(source) && Boolean.TRUE.equals(sync);
        if (!doSync) {
            return;
        }
        DietGoal goal = goalService.current(tenantId, peopleId);
        DietGoalDto saved = goalService.saveByStaff(tenantId, peopleId, staffId, new GoalRequest(
                goal == null ? null : goal.getGoalType(),
                goal == null ? null : goal.getRateKgPerWeek(),
                goal == null ? null : goal.getActivityLevel(),
                null,
                null,
                target.path("energyKcal").asInt(),
                "随管理方案发布"));
        ObjectNode synced = node(saved.getEnergyKcal(), saved.getCarbG(), saved.getProteinG(), saved.getFatG(),
                saved.getGoalType(), saved.getRateKgPerWeek(), SOURCE_STAFF_GOAL);
        diet.set(FIELD, synced);
        fixHint(diet, synced);
    }

    private ObjectNode planEdit(String tenantId, String peopleId, JsonNode existing) {
        int kcal = existing.path("energyKcal").asInt(0);
        if (kcal < 800 || kcal > 5000) {
            throw new BusinessException(400, "方案每日热量需在 800–5000 kcal 之间");
        }
        MacroRatio ratio = EnergyTargetCalculator.macroRatio(dietContextLoader.load(tenantId, peopleId).diseaseKey());
        String goalType = existing.path("goalType").asText(null);
        Double rate = existing.hasNonNull("rateKgPerWeek") ? existing.get("rateKgPerWeek").asDouble() : null;
        return node(kcal,
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
        if (placeholder || mismatched) {
            diet.put("calorieHint", defaultHint(target));
        }
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
                "每日约 %d 千卡（碳水 %dg / 蛋白质 %dg / 脂肪 %dg）",
                t.path("energyKcal").asInt(), t.path("carbG").asInt(), t.path("proteinG").asInt(), t.path("fatG").asInt()));
        DietGoalTypeEnum type = parseGoalType(t.path("goalType").asText(null));
        double rate = t.path("rateKgPerWeek").asDouble(0);
        if (type == DietGoalTypeEnum.LOSE && rate > 0) {
            sb.append(String.format("，按每周减重 %.1f kg", rate));
        } else if (type == DietGoalTypeEnum.GAIN && rate > 0) {
            sb.append(String.format("，按每周增重 %.1f kg", rate));
        } else {
            sb.append("，以维持体重为目标");
        }
        if (SOURCE_ESTIMATED.equals(t.path("source").asText())) {
            sb.append("（按身高体重估算）");
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
