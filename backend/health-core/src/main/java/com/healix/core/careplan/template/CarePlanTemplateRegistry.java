package com.healix.core.careplan.template;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.guideline.GuidelineCheck;
import com.healix.core.careplan.guideline.GuidelineMatch;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.support.CarePlanDiabetesDietGuard;
import com.healix.core.careplan.support.CarePlanDietNotesGuard;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanContextService.MetricBrief;
import com.healix.core.careplan.support.CarePlanDiseaseTags;
import com.healix.core.careplan.support.CarePlanDiseaseTags.Flags;
import com.healix.core.careplan.support.CarePlanRationaleSupport;
import com.healix.core.careplan.support.CarePlanExecutionTaskNormalizer;
import com.healix.core.careplan.support.CarePlanWeighTaskNormalizer;
import com.healix.core.careplan.support.MetricTaskRules;
import com.healix.core.water.support.WaterIntakeRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            /** 专项类型与档案确诊不符（原 noDiseaseTag 语义已废弃） */
            boolean specialtyMismatch,
            boolean hypoglycemiaRisk,
            GuidelineMatch guidelines) {

        /** @deprecated 使用 {@link #specialtyMismatch()} */
        @Deprecated
        public boolean noDiseaseTag() {
            return specialtyMismatch;
        }
    }

    public CarePlanTemplateKeyEnum resolveTemplateKey(List<String> diseaseCodes, String overrideKey) {
        if (StringUtils.hasText(overrideKey)) {
            CarePlanTemplateKeyEnum parsed = CarePlanTemplateKeyEnum.parseOrNull(overrideKey);
            if (parsed != null) {
                return parsed;
            }
            return CarePlanTemplateKeyEnum.valueOf(overrideKey.trim().toUpperCase(Locale.ROOT)).canonical();
        }
        return CarePlanDiseaseTags.recommend(diseaseCodes);
    }

    public GeneratedPlan generate(
            CarePlanTemplateKeyEnum key, CarePlanContext ctx, String instruction, List<String> diseaseTagsOverride) {
        CarePlanTemplateKeyEnum templateKey =
                (key == null ? CarePlanTemplateKeyEnum.WELLNESS : key).canonical();
        boolean hypo = ctx != null && ctx.hypoglycemiaRisk();
        boolean incomplete = ctx == null || ctx.contextIncomplete();
        List<String> rawCodes = diseaseTagsOverride != null && !diseaseTagsOverride.isEmpty()
                ? diseaseTagsOverride
                : (ctx == null ? List.of() : ctx.diseaseCodes());
        Flags flags = CarePlanDiseaseTags.detect(rawCodes);
        List<String> diseaseTags = CarePlanDiseaseTags.mergeDisplayTags(rawCodes, flags);
        boolean specialtyMismatch = CarePlanDiseaseTags.specialtyMismatch(templateKey, rawCodes);
        List<FoodAllergen> allergens = ctx == null ? List.of() : ctx.foodAllergens();
        List<String> meds = ctx == null ? List.of() : ctx.activeMedications();

        ObjectNode exercise = buildExercise(templateKey, hypo, flags);
        ObjectNode diet = buildDiet(templateKey, allergens, meds, flags, ctx);
        ObjectNode execution = buildExecution(templateKey, hypo, walkDurationMin(templateKey, hypo), ctx, flags);

        ObjectNode snapshot = ctx != null && ctx.snapshot() != null
                ? ctx.snapshot().deepCopy()
                : JsonUtils.emptyObject();
        snapshot.put("templateKey", templateKey.name());
        snapshot.put("contextIncomplete", incomplete);
        snapshot.put("hypoglycemiaRisk", hypo);
        snapshot.put("specialtyMismatch", specialtyMismatch);
        if (StringUtils.hasText(instruction)) {
            snapshot.put("instruction", instruction);
        }
        ArrayNode tags = snapshot.putArray("diseaseTags");
        diseaseTags.forEach(tags::add);
        CarePlanRationaleSupport.putOverview(
                snapshot,
                templateKey,
                CarePlanRationaleSupport.defaultOverview(templateKey, specialtyMismatch),
                "diseaseArchive",
                "template");
        GuidelineMatch guidelines = ctx == null || ctx.guidelines() == null ? GuidelineMatch.DISABLED : ctx.guidelines();
        GuidelineCheck.attachTemplateRefs(exercise, diet, execution, guidelines);

        return new GeneratedPlan(
                templateKey,
                diseaseTags,
                exercise,
                diet,
                execution,
                snapshot,
                incomplete,
                specialtyMismatch,
                hypo,
                guidelines);
    }

    private static int walkDurationMin(CarePlanTemplateKeyEnum key, boolean hypoRisk) {
        if (hypoRisk) {
            return 20;
        }
        return key.canonical() == CarePlanTemplateKeyEnum.WELLNESS ? 25 : 30;
    }

    private static String walkIntensity(boolean hypoRisk) {
        return hypoRisk ? "LIGHT" : "MODERATE";
    }

    private ObjectNode buildExercise(CarePlanTemplateKeyEnum key, boolean hypoRisk, Flags flags) {
        ObjectNode ex = JsonUtils.emptyObject();
        String intensity = walkIntensity(hypoRisk);
        int duration = walkDurationMin(key, hypoRisk);
        boolean diabetesTone = key == CarePlanTemplateKeyEnum.DIABETES
                || key == CarePlanTemplateKeyEnum.COMPREHENSIVE && flags.diabetes();
        switch (key.canonical()) {
            case DIABETES -> {
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
                ex.put("rationale", "糖尿病管理：优先餐后步行" + (hypoRisk ? "，并按低血糖风险降低强度" : ""));
            }
            case HYPERTENSION -> {
                ex.put("goal", "每周中等强度有氧 ≥150 分钟，避免憋气用力");
                array(ex, "contraindications", "血压明显升高时剧烈运动");
                array(ex, "precautions", "热身与放松各 5–10 分钟", "避免突然体位改变");
                ex.put("reviewHint", "2 周后根据家庭血压调整强度");
                ex.put("rationale", "高血压管理：中等强度有氧，避免憋气与突然体位改变");
            }
            case OBESITY -> {
                ex.put("goal", "每周中等强度有氧 ≥150 分钟，循序渐进，结合可耐受的力量练习");
                array(ex, "contraindications", "关节疼痛时强行负重");
                array(ex, "precautions", "循序渐进", "注意关节保护与补水");
                ex.put("reviewHint", "2 周后根据体重与耐受度调整时长");
                ex.put("rationale", "肥胖管理：以可持续有氧为主，便于形成热量消耗习惯");
            }
            case COMPREHENSIVE -> {
                ex.put(
                        "goal",
                        hypoRisk
                                ? "每周低～中等强度活动累计，优先短时步行，兼顾多病共管安全"
                                : "每周中等强度有氧 ≥150 分钟，兼顾控糖/控压/体重目标");
                array(ex, "contraindications", "身体不适时强行运动", "空腹剧烈运动");
                array(ex, "precautions", "循序渐进", hypoRisk ? "运动前后监测血糖" : "热身 5 分钟");
                ex.put("reviewHint", "2 周后根据指标与耐受度综合调整");
                ex.put("rationale", "综合管理：取保守合并原则" + (hypoRisk ? "，低血糖约束优先" : ""));
            }
            default -> {
                ex.put("goal", "每周累计中等强度活动 ≥150 分钟");
                array(ex, "contraindications", "身体不适时强行运动");
                array(ex, "precautions", "循序渐进", "注意补水");
                ex.put("reviewHint", "按耐受度逐步增加");
                ex.put("rationale", "健康管理：一般活跃度目标，循序渐进");
            }
        }
        ex.putObject("targets").put("weeklyActiveMin", hypoRisk ? 0 : 150);
        ArrayNode weekly = ex.putArray("weeklyPlan");
        for (String day : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) {
            ObjectNode d = weekly.addObject();
            d.put("day", day);
            ArrayNode items = d.putArray("items");
            ObjectNode item = items.addObject();
            item.put("type", "WALKING");
            item.put("durationMin", duration);
            item.put("intensity", intensity);
            item.put("note", diabetesTone ? "餐后 1 小时" : "舒适可交谈强度");
        }
        return ex;
    }

    private ObjectNode buildDiet(
            CarePlanTemplateKeyEnum key,
            List<FoodAllergen> allergens,
            List<String> meds,
            Flags flags,
            CarePlanContext ctx) {
        List<FoodAllergen> all = allergens == null ? List.of() : allergens;
        List<FoodAllergen> strict = all.stream().filter(a -> !a.intolerance()).toList();
        ObjectNode diet = JsonUtils.emptyObject();
        diet.put("calorieHint", "按身高体重与活动量个体化，避免极端节食");
        switch (key.canonical()) {
            case DIABETES -> {
                array(diet, "principles", "控制精制糖", "规律三餐", "增加膳食纤维");
                diet.put("rationale", "糖尿病饮食原则：控精制糖、规律进餐、增加纤维");
            }
            case HYPERTENSION -> {
                array(diet, "principles", "低盐", "多蔬果", "限制腌制食品");
                diet.put("rationale", "高血压饮食原则：低盐、多蔬果、限腌制");
            }
            case OBESITY -> {
                array(diet, "principles", "控制总热量", "高蛋白足蔬果", "减少含糖饮料与油炸");
                diet.put("rationale", "肥胖饮食原则：制造可持续热量缺口，保证蛋白与蔬果");
            }
            case COMPREHENSIVE -> {
                List<String> principles = new ArrayList<>();
                if (flags.diabetes() || key == CarePlanTemplateKeyEnum.COMPREHENSIVE) {
                    principles.add("控制精制糖");
                    principles.add("规律三餐");
                }
                if (flags.hypertension() || flags.count() == 0) {
                    principles.add("低盐");
                }
                if (flags.obesity() || flags.count() >= 2) {
                    principles.add("控制总热量");
                }
                principles.add("足量蔬果");
                ArrayNode arr = diet.putArray("principles");
                principles.stream().distinct().forEach(arr::add);
                diet.put("rationale", "综合管理饮食：合并控糖/低盐/热量等保守原则");
            }
            default -> {
                array(diet, "principles", "均衡饮食", "少油少糖", "足量蔬果");
                diet.put("rationale", "健康管理饮食：均衡少油少糖，非营养单科处方");
            }
        }
        ArrayNode recommended = diet.putArray("recommended");
        addFood(recommended, "OATS", "燕麦");
        addFood(recommended, "LEAFY_GREENS", "绿叶菜");
        addFood(recommended, "LEAN_PROTEIN", pick(strict, "瘦肉/豆制品", "瘦肉/禽肉"));
        ArrayNode limited = diet.putArray("limited");
        limited.add(food("SUGARY_DRINK", "含糖饮料"));
        if (flags.hypertension()
                || key == CarePlanTemplateKeyEnum.HYPERTENSION
                || key == CarePlanTemplateKeyEnum.COMPREHENSIVE) {
            limited.add(food("PICKLED", "高盐腌制品"));
        }
        ObjectNode sample = diet.putObject("sampleDay");
        ObjectNode breakfast = sample.putObject("breakfast");
        // 糖尿病禁粥：用固态/稠燕麦或杂粮饭，避免稀流质升糖
        String breakfastStaple =
                flags.diabetes() || key == CarePlanTemplateKeyEnum.DIABETES
                        ? "杂粮饭小半碗"
                        : "原味燕麦片（少水冲稠）";
        breakfast.put("text", breakfastStaple + " + " + pick(strict, "鸡蛋", "无糖豆浆", "瘦肉末") + " + 蔬菜");
        if (CarePlanAllergenGuard.firstHit("鸡蛋", strict).isEmpty()) {
            breakfast.put("swapHint", "鸡蛋可换成豆腐或无糖豆浆");
        }
        breakfast.put("rationale", "粗粮主食替代精制主食，搭配优质蛋白与蔬菜");
        ObjectNode lunch = sample.putObject("lunch");
        lunch.put("text", "杂粮饭 + 瘦肉 + 大量蔬菜");
        lunch.put(
                "swapHint",
                !strict.isEmpty()
                        ? "过敏食材请换成：" + pick(strict, "杂粮饭+瘦肉", "荞麦面", "杂粮饭")
                        : "杂粮饭可换成荞麦面");
        lunch.put("rationale", "午餐保证蔬菜量与杂粮主食");
        ObjectNode dinner = sample.putObject("dinner");
        dinner.put("text", "清淡主食 + " + pick(strict, "鱼/豆制品", "鱼", "豆制品", "去皮禽肉") + " + 蔬菜");
        if (!strict.isEmpty()) {
            dinner.put("swapHint", "过敏食材请换成：" + pick(strict, "豆腐/瘦肉", "豆制品", "去皮禽肉", "绿叶菜"));
        }
        dinner.put("rationale", "晚餐清淡、控制油盐");
        sample.put("snacks", pick(strict, "原味坚果少量或无糖酸奶", "无糖酸奶", "原味坚果少量", "低糖水果（如苹果、柚子）"));
        List<String> noteParts = new ArrayList<>();
        if (meds != null && !meds.isEmpty()) {
            noteParts.add("在用药物：" + String.join("、", meds.stream().limit(8).toList()) + "；请按药盒时机（饭前/饭后）配合进餐，勿自行停药。");
        }
        if (!strict.isEmpty()) {
            noteParts.add("外食技巧：外出就餐时主动询问菜品成分，避开已知过敏原。");
        }
        List<String> intolerances = all.stream().filter(FoodAllergen::intolerance).map(FoodAllergen::name).toList();
        if (!intolerances.isEmpty()) {
            noteParts.add("不耐受食物（"
                    + String.join("、", intolerances)
                    + "）可少量尝试，不宜作为日常主推。");
        }
        appendLifestyleDietNotes(noteParts, ctx);
        diet.put("notes", String.join("；", noteParts));
        CarePlanAllergenGuard.enforce(diet, all);
        CarePlanAllergenGuard.sanitize(diet, all);
        CarePlanDiabetesDietGuard.sanitize(diet, key);
        CarePlanDietNotesGuard.sanitize(diet);
        return diet;
    }

    private static String pick(List<FoodAllergen> strict, String... options) {
        for (String o : options) {
            if (CarePlanAllergenGuard.firstHit(o, strict).isEmpty()) {
                return o;
            }
        }
        return options[options.length - 1];
    }

    /** 作息 / 做饭人 → 模板兜底 notes（LLM 生成时由 Skill 再强化）。 */
    private static void appendLifestyleDietNotes(List<String> noteParts, CarePlanContext ctx) {
        if (ctx == null || ctx.snapshot() == null || noteParts == null) {
            return;
        }
        JsonNode lifestyle = ctx.snapshot().get("lifestyleProfile");
        if (lifestyle != null && lifestyle.isObject()) {
            String hint = lifestyle.path("scheduleHint").asText("");
            String label = lifestyle.path("workScheduleLabel").asText("");
            if (StringUtils.hasText(hint)) {
                noteParts.add(StringUtils.hasText(label) ? "作息（" + label + "）：" + hint + "。" : hint + "。");
            }
        }
        String cookCode = ctx.snapshot().path("basicArchive").path("diet").path("cook").asText("").trim();
        String cookLabel = ctx.snapshot().path("basicArchive").path("diet").path("cookLabel").asText("").trim();
        String cookUpper = cookCode.toUpperCase(Locale.ROOT);
        if ("FAMILY".equals(cookUpper) || cookLabel.contains("家人")) {
            noteParts.add("请家人少放油盐、主食尽量换成杂粮，外食时帮忙盯忌口。");
        } else if ("EAT_OUT".equals(cookUpper) || cookLabel.contains("外卖") || cookLabel.contains("外食")) {
            noteParts.add("外卖/外食为主：点菜少油少糖、酱料另加，优先蒸煮烤。");
        } else if ("CANTEEN".equals(cookUpper) || cookLabel.contains("食堂")) {
            noteParts.add("食堂就餐：少选红烧油炸，汤汁少喝，主食换杂粮若有。");
        }
        JsonNode details = ctx.snapshot().get("activeMedicationDetails");
        if (details != null && details.isArray()) {
            List<String> tips = new ArrayList<>();
            for (JsonNode d : details) {
                if (d == null || !d.isObject()) {
                    continue;
                }
                String name = d.path("name").asText("");
                String timing = d.path("timingNote").asText("");
                if (StringUtils.hasText(name) && StringUtils.hasText(timing)) {
                    tips.add(name + "（" + timing + "）");
                }
            }
            if (!tips.isEmpty()) {
                noteParts.add("药-餐时机：" + String.join("、", tips.stream().limit(4).toList()) + "。");
            }
        }
    }

    private ObjectNode buildExecution(
            CarePlanTemplateKeyEnum key,
            boolean hypoRisk,
            int walkMin,
            CarePlanContext ctx,
            Flags flags) {
        ObjectNode exec = JsonUtils.emptyObject();
        exec.put("horizonDays", 14);
        ArrayNode tasks = exec.putArray("tasks");
        String walkSlot = preferExerciseSlot(ctx, "AFTER_DINNER");
        String walkFreq = "WEEKEND".equalsIgnoreCase(firstAvailableSlot(ctx)) ? "TIW" : "QD";
        ObjectNode walk = task(
                "EX_WALK_30",
                (hypoRisk ? "短时步行 " : "步行 ") + walkMin + " 分钟",
                "EXERCISE",
                walkFreq,
                walkSlot,
                "WALKING",
                "落实运动方案中的步行目标");
        ObjectNode payload = walk.putObject("payload");
        payload.put("targetMin", walkMin);
        payload.put("intensity", walkIntensity(hypoRisk));
        tasks.add(walk);
        tasks.add(task("DIET_LOG", "记录三餐与加餐", "DIET", "QD", "EVENING", null, "便于回顾饮食并对照示例日"));
        if (!WaterIntakeRules.fluidRestricted(
                ctx == null ? List.of() : ctx.diseaseCodes(), ctx == null ? null : ctx.snapshot())) {
            Double kg = weightKg(ctx);
            int targetMl = WaterIntakeRules.suggestTargetMl(kg);
            ObjectNode water = task(
                    WaterIntakeRules.TASK_CODE,
                    "足量饮水，每日约 " + targetMl + " ml",
                    WaterIntakeRules.CATEGORY,
                    "QD",
                    "ALL",
                    null,
                    CarePlanExecutionTaskNormalizer.waterRationale(kg, targetMl));
            water.put("targetMl", targetMl);
            ObjectNode waterPayload = water.putObject("payload");
            waterPayload.put("targetMl", targetMl);
            tasks.add(water);
        }
        // 随餐药 → 独立用药提醒，勿塞进饮食记录标题
        appendMealMedicationReminder(tasks, ctx);
        tasks.add(task(
                com.healix.core.sleep.support.SleepLogRules.TASK_CODE,
                "记录昨夜睡眠",
                com.healix.core.sleep.support.SleepLogRules.CATEGORY,
                "QD",
                "MORNING",
                null,
                "生活方式基线任务"));
        tasks.add(task(
                com.healix.core.mood.support.MoodLogRules.TASK_CODE,
                "记录今日心情",
                com.healix.core.mood.support.MoodLogRules.CATEGORY,
                "QD",
                "EVENING",
                null,
                "生活方式基线任务"));
        // 监测按病种标签叠加（与 templateKey 解耦）
        if (flags.diabetes()) {
            tasks.add(task(
                    "GLUCOSE_CHECK",
                    "按时监测血糖",
                    "OTHER",
                    "QD",
                    "MORNING",
                    MetricTaskRules.REF_GLUCOSE,
                    "档案含糖尿病，叠加血糖监测"));
        }
        if (flags.hypertension()) {
            tasks.add(task(
                    "BP_HOME",
                    "家庭血压测量",
                    "OTHER",
                    "QD",
                    "MORNING",
                    MetricTaskRules.REF_BP,
                    "档案含高血压，叠加家庭血压监测"));
        }
        if (flags.obesity() || key == CarePlanTemplateKeyEnum.OBESITY) {
            boolean weeklyWeigh = CarePlanWeighTaskNormalizer.preferWeeklyWeigh(ctx == null ? null : ctx.snapshot());
            tasks.add(task(
                    "WEIGHT_LOG",
                    weeklyWeigh ? "每周固定一天晨起空腹称体重" : "记录体重",
                    "OTHER",
                    weeklyWeigh ? "QW" : "QD",
                    "MORNING",
                    MetricTaskRules.REF_WEIGHT,
                    weeklyWeigh
                            ? "进食障碍/身材焦虑等高亮命中：称重改为每周1次"
                            : (flags.obesity() ? "档案含肥胖，每日记录体重" : "减重管理：每日记录体重")));
        }
        exec.put("rationale", "执行清单含基础生活任务，并按档案病种标签叠加监测");
        return exec;
    }

    private static String firstAvailableSlot(CarePlanContext ctx) {
        if (ctx == null || ctx.snapshot() == null) {
            return null;
        }
        JsonNode slots = ctx.snapshot().path("basicArchive").path("exercise").path("availableSlots");
        if (slots != null && slots.isArray() && !slots.isEmpty()) {
            JsonNode first = slots.get(0);
            if (first != null && first.isTextual()) {
                return first.asText();
            }
            if (first != null && first.isObject()) {
                String code = first.path("code").asText("");
                if (StringUtils.hasText(code)) {
                    return code;
                }
            }
        }
        return null;
    }

    private static String preferExerciseSlot(CarePlanContext ctx, String fallback) {
        String slot = firstAvailableSlot(ctx);
        if (!StringUtils.hasText(slot)) {
            return fallback;
        }
        return switch (slot.trim().toUpperCase(Locale.ROOT)) {
            case "MORNING" -> "MORNING";
            case "NOON" -> "AFTERNOON";
            case "EVENING" -> "EVENING";
            case "NIGHT" -> "AFTER_DINNER";
            case "WEEKEND" -> "AFTERNOON";
            default -> fallback;
        };
    }

    /** 对已写入的 tasks 跑一遍执行清单硬约束（饮水文案 / 随餐药拆分等）。 */
    private static void appendMealMedicationReminder(ArrayNode tasks, CarePlanContext ctx) {
        if (ctx == null || tasks == null) {
            return;
        }
        ObjectNode exec = JsonUtils.emptyObject();
        exec.set("tasks", tasks);
        CarePlanExecutionTaskNormalizer.normalize(exec, ctx.snapshot());
    }

    private static Double weightKg(CarePlanContext ctx) {
        if (ctx == null || ctx.latestMetrics() == null) {
            return null;
        }
        for (MetricBrief m : ctx.latestMetrics()) {
            if (m != null && "WEIGHT".equalsIgnoreCase(m.metricType()) && StringUtils.hasText(m.value())) {
                try {
                    return Double.parseDouble(m.value().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
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
            String code,
            String title,
            String category,
            String frequency,
            String timeSlot,
            String relatedRef,
            String rationale) {
        ObjectNode t = JsonUtils.emptyObject();
        t.put("code", code);
        t.put("title", title);
        t.put("category", category);
        t.put("frequency", frequency);
        t.put("timeSlot", timeSlot);
        if (relatedRef != null) {
            t.put("relatedRef", relatedRef);
        }
        if (StringUtils.hasText(rationale)) {
            t.put("rationale", rationale);
        }
        t.put("enabled", true);
        return t;
    }
}
