package com.healix.core.careplan.template;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanSafetyService;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CarePlanTemplateRegistry {

    public record GeneratedPlan(
            CarePlanTemplateKeyEnum templateKey,
            List<String> diseaseTags,
            ObjectNode exercise,
            ObjectNode diet,
            ObjectNode execution,
            ObjectNode contextSnapshot,
            boolean contextIncomplete,
            boolean noDiseaseTag,
            boolean hypoglycemiaRisk) {}

    public CarePlanTemplateKeyEnum resolveTemplateKey(List<String> diseaseCodes, String overrideKey) {
        if (StringUtils.hasText(overrideKey)) {
            return CarePlanTemplateKeyEnum.valueOf(overrideKey.trim().toUpperCase(Locale.ROOT));
        }
        boolean diabetes = false;
        boolean hypertension = false;
        if (diseaseCodes != null) {
            for (String c : diseaseCodes) {
                if (c == null) {
                    continue;
                }
                String u = c.trim().toUpperCase(Locale.ROOT);
                if (u.contains("DIABETES") || "糖尿病".equals(c.trim())) {
                    diabetes = true;
                }
                if (u.contains("HYPERTENSION") || "高血压".equals(c.trim())) {
                    hypertension = true;
                }
            }
        }
        if (diabetes && hypertension) {
            return CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION;
        }
        if (diabetes) {
            return CarePlanTemplateKeyEnum.DIABETES;
        }
        if (hypertension) {
            return CarePlanTemplateKeyEnum.HYPERTENSION;
        }
        return CarePlanTemplateKeyEnum.GENERAL;
    }

    public GeneratedPlan generate(
            CarePlanTemplateKeyEnum key, CarePlanContext ctx, String instruction, List<String> diseaseTagsOverride) {
        CarePlanTemplateKeyEnum templateKey = key == null ? CarePlanTemplateKeyEnum.GENERAL : key;
        boolean noDisease = templateKey == CarePlanTemplateKeyEnum.GENERAL;
        boolean hypo = ctx != null && ctx.hypoglycemiaRisk();
        boolean incomplete = ctx == null || ctx.contextIncomplete();
        List<String> diseaseTags = diseaseTagsOverride != null && !diseaseTagsOverride.isEmpty()
                ? diseaseTagsOverride
                : (ctx == null ? List.of() : ctx.diseaseCodes());
        List<String> allergens = ctx == null ? List.of() : ctx.allergenNames();
        List<String> meds = ctx == null ? List.of() : ctx.activeMedications();

        ObjectNode exercise = buildExercise(templateKey, hypo);
        ObjectNode diet = buildDiet(templateKey, allergens, meds);
        ObjectNode execution = buildExecution(templateKey, hypo);

        ObjectNode snapshot = ctx != null && ctx.snapshot() != null
                ? ctx.snapshot().deepCopy()
                : JsonUtils.emptyObject();
        snapshot.put("templateKey", templateKey.name());
        snapshot.put("contextIncomplete", incomplete);
        snapshot.put("hypoglycemiaRisk", hypo);
        if (StringUtils.hasText(instruction)) {
            snapshot.put("instruction", instruction);
        }
        ArrayNode tags = snapshot.putArray("diseaseTags");
        diseaseTags.forEach(tags::add);

        return new GeneratedPlan(
                templateKey,
                diseaseTags,
                exercise,
                diet,
                execution,
                snapshot,
                incomplete,
                noDisease,
                hypo);
    }

    private ObjectNode buildExercise(CarePlanTemplateKeyEnum key, boolean hypoRisk) {
        ObjectNode ex = JsonUtils.emptyObject();
        String intensity = hypoRisk ? "LIGHT" : "MODERATE";
        int duration = hypoRisk ? 20 : (key == CarePlanTemplateKeyEnum.GENERAL ? 25 : 30);
        switch (key) {
            case DIABETES, DIABETES_HYPERTENSION -> {
                ex.put(
                        "goal",
                        hypoRisk
                                ? "每周低～中等强度活动累计，优先短时餐后步行，避免低血糖时段运动"
                                : "每周中等强度有氧 ≥150 分钟，优先餐后步行");
                array(ex, "contraindications", "空腹剧烈运动", "血糖过低时运动");
                array(
                        ex,
                        "precautions",
                        "随身携带糖块",
                        "出现心慌头晕立即停止",
                        hypoRisk ? "运动前后监测血糖" : "热身 5 分钟");
                ex.put("reviewHint", "2 周后根据空腹/餐后血糖调整时长");
            }
            case HYPERTENSION -> {
                ex.put("goal", "每周中等强度有氧 ≥150 分钟，避免憋气用力");
                array(ex, "contraindications", "血压明显升高时剧烈运动");
                array(ex, "precautions", "热身与放松各 5–10 分钟", "避免突然体位改变");
                ex.put("reviewHint", "2 周后根据家庭血压调整强度");
            }
            default -> {
                ex.put("goal", "每周累计中等强度活动 ≥150 分钟");
                array(ex, "contraindications", "身体不适时强行运动");
                array(ex, "precautions", "循序渐进", "注意补水");
                ex.put("reviewHint", "按耐受度逐步增加");
            }
        }
        ArrayNode weekly = ex.putArray("weeklyPlan");
        for (String day : List.of("MON", "WED", "FRI")) {
            ObjectNode d = weekly.addObject();
            d.put("day", day);
            ArrayNode items = d.putArray("items");
            ObjectNode item = items.addObject();
            item.put("type", "WALKING");
            item.put("durationMin", duration);
            item.put("intensity", intensity);
            item.put(
                    "note",
                    key == CarePlanTemplateKeyEnum.DIABETES || key == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION
                            ? "餐后 1 小时"
                            : "舒适可交谈强度");
        }
        return ex;
    }

    private ObjectNode buildDiet(CarePlanTemplateKeyEnum key, List<String> allergenNames, List<String> meds) {
        ObjectNode diet = JsonUtils.emptyObject();
        diet.put("calorieHint", "按身高体重与活动量个体化，避免极端节食");
        switch (key) {
            case DIABETES, DIABETES_HYPERTENSION -> array(diet, "principles", "控制精制糖", "规律三餐", "增加膳食纤维");
            case HYPERTENSION -> array(diet, "principles", "低盐", "多蔬果", "限制腌制食品");
            default -> array(diet, "principles", "均衡饮食", "少油少糖", "足量蔬果");
        }
        ArrayNode recommended = diet.putArray("recommended");
        addFood(recommended, "OATS", "燕麦");
        addFood(recommended, "LEAFY_GREENS", "绿叶菜");
        addFood(recommended, "LEAN_PROTEIN", "瘦肉/豆制品");
        ArrayNode limited = diet.putArray("limited");
        limited.add(food("SUGARY_DRINK", "含糖饮料"));
        if (key == CarePlanTemplateKeyEnum.HYPERTENSION || key == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION) {
            limited.add(food("PICKLED", "高盐腌制品"));
        }
        ArrayNode avoid = diet.putArray("allergensAvoid");
        Set<String> seen = new LinkedHashSet<>();
        if (allergenNames != null) {
            for (String name : allergenNames) {
                if (!StringUtils.hasText(name)) {
                    continue;
                }
                String code = CarePlanSafetyService.normalizeCode(name);
                if (code == null || !seen.add(code)) {
                    continue;
                }
                ObjectNode a = avoid.addObject();
                a.put("code", code);
                a.put("label", name.trim());
            }
        }
        ObjectNode sample = diet.putObject("sampleDay");
        sample.put("breakfast", "燕麦粥 + 鸡蛋 + 蔬菜");
        sample.put("lunch", "杂粮饭 + 瘦肉 + 大量蔬菜");
        sample.put("dinner", "清淡主食 + 鱼/豆制品 + 蔬菜");
        sample.put("snacks", "原味坚果少量或无糖酸奶（无过敏时）");
        String notes = "";
        if (meds != null && !meds.isEmpty()) {
            notes = "在用药物：" + String.join("、", meds.stream().limit(8).toList()) + "；饮食需结合药物说明。";
        }
        diet.put("notes", notes);
        return diet;
    }

    private ObjectNode buildExecution(CarePlanTemplateKeyEnum key, boolean hypoRisk) {
        ObjectNode exec = JsonUtils.emptyObject();
        exec.put("horizonDays", 14);
        ArrayNode tasks = exec.putArray("tasks");
        tasks.add(task(
                "EX_WALK_30",
                hypoRisk ? "短时步行 20 分钟" : "步行 30 分钟",
                "EXERCISE",
                "QD",
                "AFTER_DINNER",
                "WALKING"));
        tasks.add(task("DIET_LOG", "记录三餐", "DIET", "QD", "EVENING", null));
        if (key == CarePlanTemplateKeyEnum.DIABETES || key == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION) {
            tasks.add(task("GLUCOSE_CHECK", "按医嘱监测血糖", "OTHER", "QD", "MORNING", "BLOOD_GLUCOSE"));
        }
        if (key == CarePlanTemplateKeyEnum.HYPERTENSION || key == CarePlanTemplateKeyEnum.DIABETES_HYPERTENSION) {
            tasks.add(task("BP_HOME", "家庭血压测量", "OTHER", "QD", "MORNING", "BLOOD_PRESSURE"));
        }
        return exec;
    }

    private void array(ObjectNode parent, String field, String... values) {
        ArrayNode arr = parent.putArray(field);
        for (String v : values) {
            arr.add(v);
        }
    }

    private void addFood(ArrayNode arr, String code, String label) {
        arr.add(food(code, label));
    }

    private ObjectNode food(String code, String label) {
        ObjectNode n = JsonUtils.emptyObject();
        n.put("code", code);
        n.put("label", label);
        return n;
    }

    private ObjectNode task(
            String code, String title, String category, String frequency, String timeSlot, String relatedRef) {
        ObjectNode t = JsonUtils.emptyObject();
        t.put("code", code);
        t.put("title", title);
        t.put("category", category);
        t.put("frequency", frequency);
        t.put("timeSlot", timeSlot);
        if (relatedRef != null) {
            t.put("relatedRef", relatedRef);
        }
        t.put("enabled", true);
        return t;
    }
}
