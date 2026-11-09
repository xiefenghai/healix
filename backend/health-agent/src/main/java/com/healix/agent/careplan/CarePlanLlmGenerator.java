package com.healix.agent.careplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.support.SkillMarkdown;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.guideline.GuidelineMatch;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanPromptContextSupport;
import com.healix.core.careplan.template.CarePlanTemplateRegistry.GeneratedPlan;
import com.healix.core.compliance.ComplianceText;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class CarePlanLlmGenerator {

    private final LlmClient llmClient;
    public record LlmPlanResult(
            GeneratedPlan plan, String summary, String goalSummary, LlmResponse llmResponse) {}

    public Optional<LlmPlanResult> tryGenerate(
            CarePlanTemplateKeyEnum templateKey,
            CarePlanContext ctx,
            String instruction,
            List<String> diseaseCodes,
            List<ChatTurn> history,
            Consumer<AgentStreamEvent> sink) {
        if (!llmClient.isEnabled()) {
            log.info("[CarePlan] LLM disabled, will use template fallback templateKey={}", templateKey);
            return Optional.empty();
        }
        List<ChatTurn> turns = history == null ? List.of() : history;
        log.info(
                "[CarePlan] LLM generate start templateKey={} diseaseCodes={} instructionChars={} historyTurns={} contextIncomplete={}",
                templateKey,
                diseaseCodes == null || diseaseCodes.isEmpty() ? "[]" : diseaseCodes,
                instruction == null ? 0 : instruction.length(),
                turns.size(),
                ctx != null && ctx.contextIncomplete());
        CarePlanStreamSink.progress(sink, "正在翻阅方案生成指引与临床共识…");
        String skill = loadSkillMarkdown();
        String userMessage = buildUserMessage(templateKey, ctx, instruction, diseaseCodes);
        CarePlanStreamSink.progress(sink, "正在结合指南起草运动、饮食与执行计划…");
        long thinkStarted = System.currentTimeMillis();
        CarePlanStreamSink.thinkingStart(sink, "规划运动、饮食与执行结构…");
        CarePlanStreamSink.tool(sink, "临床方案推理", "running", "正在综合患者数据与指南起草方案…");
        CarePlanReadableStreamer readable = new CarePlanReadableStreamer(sink);
        LlmResponse llm = llmClient.streamChat(
                "CARE_PLAN_GENERATE",
                skill,
                userMessage,
                turns,
                readable::onToken);
        return finalizeLlmResult(llm, templateKey, ctx, diseaseCodes, sink, thinkStarted, false);
    }

    /**
     * 在现有草稿上按健管师反馈修订；失败时返回 empty（由调用方决定是否提示重试，不静默回退模板）。
     */
    public Optional<LlmPlanResult> tryRevise(
            CarePlanTemplateKeyEnum templateKey,
            CarePlanContext ctx,
            String instruction,
            List<String> diseaseCodes,
            JsonNode currentExercise,
            JsonNode currentDiet,
            JsonNode currentExecution,
            String currentGoalSummary,
            List<ChatTurn> history,
            Consumer<AgentStreamEvent> sink) {
        if (!llmClient.isEnabled()) {
            log.info("[CarePlan] LLM disabled, cannot revise templateKey={}", templateKey);
            return Optional.empty();
        }
        List<ChatTurn> turns = history == null ? List.of() : history;
        log.info(
                "[CarePlan] LLM revise start templateKey={} instructionChars={} historyTurns={}",
                templateKey,
                instruction == null ? 0 : instruction.length(),
                turns.size());
        CarePlanStreamSink.progress(sink, "正在翻阅方案修订指引…");
        String skill = loadSkillMarkdown() + "\n\n## 修订模式补充（硬性）\n"
                + "1. 在 currentDraft 上按 revisionInstruction 局部调整；未提及部分保持原意与结构。\n"
                + "2. mustPreserveConstraints / 草稿中的骨折·卧床·制动·禁止负重等安全限制必须保留，"
                + "除非修订意见明确解除。\n"
                + "3. 饮食/消化类修订优先改 diet，禁止把运动从安全制动改回常规有氧。\n"
                + "4. 执行任务过多/过少类意见优先改 execution.tasks（删减或合并），勿无故整包重写运动饮食。\n"
                + "5. goalSummary 须同时保留仍有效的安全限制与本次新增关注。\n"
                + "6. 仍输出完整 exercise/diet/execution/summary/goalSummary JSON。\n"
                + "7. 若有会话历史，结合上文理解「改哪里」，不要忽略健管师刚说过的约束。\n"
                + "8. 糖尿病相关方案示例餐禁止粥类主食（小米粥/杂粮粥等），尤其晚餐；改用杂粮饭/荞麦面/全麦馒头。\n"
                + "9. 示例餐主食写克数或标准参照；sampleWeek 早餐鸡蛋类不超过 3 天，与豆浆/豆制品轮换。\n";
        String userMessage = buildReviseUserMessage(
                templateKey,
                ctx,
                instruction,
                diseaseCodes,
                currentExercise,
                currentDiet,
                currentExecution,
                currentGoalSummary);
        CarePlanStreamSink.progress(sink, "正在按你的意见修订方案草稿…");
        long thinkStarted = System.currentTimeMillis();
        CarePlanStreamSink.thinkingStart(sink, "对照草稿落实修订意见…");
        CarePlanStreamSink.tool(sink, "临床方案推理", "running", "正在按意见调整运动、饮食与执行计划…");
        CarePlanReadableStreamer readable = new CarePlanReadableStreamer(sink);
        LlmResponse llm = llmClient.streamChat(
                "CARE_PLAN_REVISE",
                skill,
                userMessage,
                turns,
                readable::onToken);
        return finalizeLlmResult(llm, templateKey, ctx, diseaseCodes, sink, thinkStarted, true);
    }

    private Optional<LlmPlanResult> finalizeLlmResult(
            LlmResponse llm,
            CarePlanTemplateKeyEnum templateKey,
            CarePlanContext ctx,
            List<String> diseaseCodes,
            Consumer<AgentStreamEvent> sink,
            long thinkStarted,
            boolean revise) {
        long thinkMs = System.currentTimeMillis() - thinkStarted;
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            log.warn(
                    "[CarePlan] LLM empty response, {} templateKey={}",
                    revise ? "revise failed" : "fallback to template",
                    templateKey);
            CarePlanStreamSink.tool(sink, "临床方案推理", "done", "未能完成草案，请稍后重试");
            CarePlanStreamSink.thinkingDone(sink, "暂未形成可用草案", thinkMs);
            return Optional.empty();
        }
        CarePlanStreamSink.tool(
                sink,
                "临床方案推理",
                "done",
                revise ? "修订草案已整理完成" : "运动、饮食与执行草案已整理完成");
        CarePlanStreamSink.thinkingDone(
                sink, revise ? "已按意见整理方案" : "已整理运动、饮食与执行计划", thinkMs);
        CarePlanStreamSink.progress(sink, "正在核对方案结构与关键指标…");
        try {
            LlmPlanResult result = parseLlmOutput(llm, templateKey, ctx, diseaseCodes);
            log.info(
                    "[CarePlan] LLM parse success mode={} templateKey={} goalSummary={} responseChars={}",
                    revise ? "REVISE" : "GENERATE",
                    templateKey,
                    result.goalSummary(),
                    llm.content().length());
            if (StringUtils.hasText(result.goalSummary())) {
                CarePlanStreamSink.progress(sink, "目标：" + truncate(result.goalSummary(), 48));
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn(
                    "[CarePlan] LLM parse failed mode={} templateKey={} error={}",
                    revise ? "REVISE" : "GENERATE",
                    templateKey,
                    e.getMessage());
            CarePlanStreamSink.tool(
                    sink,
                    "parseCarePlan",
                    "done",
                    revise ? "结构校验未通过" : "结构校验未通过，改用模板方案");
            return Optional.empty();
        }
    }

    private LlmPlanResult parseLlmOutput(
            LlmResponse llm,
            CarePlanTemplateKeyEnum templateKey,
            CarePlanContext ctx,
            List<String> diseaseCodes) {
        JsonNode root = parseJsonRoot(llm.content());
        ObjectNode exercise = requireObject(root, "exercise");
        ObjectNode diet = requireObject(root, "diet");
        ObjectNode execution = requireObject(root, "execution");
        String summary = textOrEmpty(root, "summary");
        String goalSummary = textOrEmpty(root, "goalSummary");
        if (!StringUtils.hasText(goalSummary)) {
            goalSummary = exercise.path("goal").asText("管理方案");
        }

        CarePlanTemplateKeyEnum key = templateKey == null
                ? CarePlanTemplateKeyEnum.WELLNESS
                : templateKey.canonical();
        boolean hypo = ctx != null && ctx.hypoglycemiaRisk();
        boolean incomplete = ctx == null || ctx.contextIncomplete();
        List<String> rawCodes = diseaseCodes != null && !diseaseCodes.isEmpty()
                ? diseaseCodes
                : (ctx == null ? List.of() : ctx.diseaseCodes());
        var flags = com.healix.core.careplan.support.CarePlanDiseaseTags.detect(rawCodes);
        List<String> tags =
                com.healix.core.careplan.support.CarePlanDiseaseTags.mergeDisplayTags(rawCodes, flags);
        boolean specialtyMismatch =
                com.healix.core.careplan.support.CarePlanDiseaseTags.specialtyMismatch(key, rawCodes);

        ObjectNode snapshot = ctx != null && ctx.snapshot() != null
                ? ctx.snapshot().deepCopy()
                : JsonUtils.emptyObject();
        snapshot.put("templateKey", key.name());
        snapshot.put("contextIncomplete", incomplete);
        snapshot.put("hypoglycemiaRisk", hypo);
        snapshot.put("specialtyMismatch", specialtyMismatch);
        snapshot.put("generationMode", "LLM");
        if (StringUtils.hasText(summary)) {
            snapshot.put("summary", summary);
        }
        JsonNode rationaleNode = root.get("rationale");
        if (rationaleNode != null && rationaleNode.isObject()) {
            snapshot.set("rationale", rationaleNode.deepCopy());
        } else {
            com.healix.core.careplan.support.CarePlanRationaleSupport.putOverview(
                    snapshot,
                    key,
                    com.healix.core.careplan.support.CarePlanRationaleSupport.defaultOverview(
                            key, specialtyMismatch),
                    "diseaseArchive",
                    "assessment",
                    "llm");
        }
        ensureBlockRationale(exercise, "AI 根据患者档案与评估生成的运动安排");
        ensureBlockRationale(diet, "AI 根据病种、过敏与用药生成的饮食安排");
        ensureBlockRationale(execution, "AI 根据方案类型与病种标签编制的执行任务");

        GuidelineMatch guidelines = ctx == null || ctx.guidelines() == null ? GuidelineMatch.DISABLED : ctx.guidelines();
        GeneratedPlan plan = new GeneratedPlan(
                key, tags, exercise, diet, execution, snapshot, incomplete, specialtyMismatch, hypo, guidelines);
        return new LlmPlanResult(plan, summary, goalSummary, llm);
    }

    private static void ensureBlockRationale(ObjectNode block, String fallback) {
        if (block != null && !StringUtils.hasText(block.path("rationale").asText(null))) {
            block.put("rationale", fallback);
        }
    }

    private static ObjectNode requireObject(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Missing object field: " + field);
        }
        return (ObjectNode) node;
    }

    private static String textOrEmpty(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private JsonNode parseJsonRoot(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
        }
        JsonNode root;
        try {
            root = JsonUtils.mapper().readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid care plan JSON", e);
        }
        filterTexts(root);
        return root;
    }

    /** 方案里的文案（标题、总结、任务名、建议）都会给患者看，统一过合规用语替换。 */
    private static void filterTexts(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            for (String name : iterable(obj.fieldNames())) {
                JsonNode v = obj.get(name);
                if (v.isTextual()) {
                    obj.put(name, ComplianceText.filter(v.asText()));
                } else {
                    filterTexts(v);
                }
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                JsonNode v = arr.get(i);
                if (v.isTextual()) {
                    arr.set(i, ComplianceText.filter(v.asText()));
                } else {
                    filterTexts(v);
                }
            }
        }
    }

    private static List<String> iterable(Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private String buildUserMessage(
            CarePlanTemplateKeyEnum templateKey,
            CarePlanContext ctx,
            String instruction,
            List<String> diseaseCodes) {
        ObjectNode payload = JsonUtils.emptyObject();
        payload.put("templateKey", templateKey.name());
        if (StringUtils.hasText(instruction)) {
            payload.put("instruction", instruction);
        }
        List<String> codes = sanitizeDiseaseCodes(diseaseCodes);
        if (!codes.isEmpty()) {
            payload.set("diseaseCodes", JsonUtils.mapper().valueToTree(codes));
        }
        payload.set("patientContext", patientContext(ctx));
        putAllergyGuard(payload, ctx);
        putGuidelineRecs(payload, ctx);
        putCareTrackHint(payload, ctx);
        putHighlightHint(payload, ctx);
        return """
                请根据以下输入生成完整管理方案 JSON。
                字段顺序必须为：exercise → diet → execution → summary → goalSummary。
                goalSummary 须写具体目标（周期+数字：体重/血压/血糖/腰围等），禁止与「综合管理方案」类短标题同义复读口号。
                减重数字必须自洽：若写「N个月减重Mkg」或「N个月内从Akg降至Bkg」，文中每周速度须≈总减重量/(N×4.3)kg（可四舍五入到0.1）；例：6个月从80kg降至60kg（减20kg）→每周约0.8kg，禁止写成每周0.5kg。calorieHint 中的每周速度须与 goalSummary 同一套数字，勿与 energyTarget.rateKgPerWeek 各写一套互相矛盾。
                叙事与监测须对齐 patientContext.goals.careTracks（及 diabetesClinical / hypertensionClinical）；
                无减重方向时不要写成减肥方案。
                若有 patientContext.highlightGuidance，须把每条 mustApply 落实到运动/饮食/执行（clinicianNote 只吸收约束，勿照抄进患者正文）。
                有食物过敏时 sampleDay 早午晚每餐须给安全 swapHint（过敏平替）；有 lifestyleProfile/cook/availableSlots/constraints/用药 timingNote 时须落实到 notes、timeSlot 与家属一句提示。
                糖尿病（含 T1/T2、胰岛素或降糖药）：示例餐禁止粥类主食（小米粥/杂粮粥/白米粥等），尤其晚餐；改用杂粮饭、荞麦面、全麦馒头；limited 须含「熬煮软烂的粥类（相对杂粮饭升糖更快…）」并温和提示少选软烂粥——固态杂粮饭可推荐，勿与粥类限制混淆。
                过敏史未采集时：recommended/示例餐不要默认推虾蟹贝、花生、坚果等高致敏物；优先瘦肉/禽肉/豆腐/淡水鱼。
                diet.notes 是患者可见「执行提醒」：短句分号分隔，禁止写医嘱/处方，禁止写过敏史未采集等内部质检话术。
                示例餐主食/加餐须写克数或标准参照（如杂粮饭约150g熟重），禁止只写「1碗/2块」；sampleWeek 早餐鸡蛋类不超过3天，与豆浆/豆制品轮换。
                若有下班时间、倒班或夜班的上班时段、晚餐时间、吃药和吃饭的配合、执行阻力（应酬/外卖/易饿/家人不支持/没时间）、早上是否解乏：按这些安排餐次和运动时段。下班晚、晚餐晚，或写了倒班/夜班时段时，不要把运动写死在傍晚；药需跟第一口饭同服时：diet.notes 写服药配合短句，并单独编 MED_REMINDER（category=MEDICATION），禁止塞进 DIET_LOG 标题；久坐/每小时起身任务 frequency 用 HABIT 而非 QD；饮水 targetMl 按 kg×30（百毫升取整，1200–3000），rationale 用大白话说明建议毫升；打鼾+晨起疲惫+超重/肥胖或糖压慢病时：diet.notes 必须写强提醒「尽快去呼吸内科/耳鼻喉科做睡眠呼吸监测（多导睡眠图）」，禁止只写「关注睡眠呼吸情况」，勿写成已确诊。
                仅输出 JSON，不要 markdown。

                %s
                """
                .formatted(JsonUtils.toJson(payload));
    }

    /** 首诊高亮追问 / 方案影响 → 生成时强制关注。 */
    private static void putHighlightHint(ObjectNode payload, CarePlanContext ctx) {
        if (ctx == null || ctx.snapshot() == null) {
            return;
        }
        JsonNode guidance = ctx.snapshot().get("highlightGuidance");
        if (guidance == null || !guidance.isArray() || guidance.isEmpty()) {
            return;
        }
        ObjectNode hint = JsonUtils.emptyObject();
        hint.put("count", guidance.size());
        hint.put(
                "rule",
                "优先落实 highlightGuidance[].mustApply；severityLabel=转介/阻断时更保守；clinicianNote 为高亮追问，勿写入患者可见正文");
        payload.set("highlightConstraint", hint);
    }

    private static void putCareTrackHint(ObjectNode payload, CarePlanContext ctx) {
        if (ctx == null || ctx.snapshot() == null) {
            return;
        }
        JsonNode goals = ctx.snapshot().get("goals");
        if (goals == null || goals.isNull()) {
            return;
        }
        ObjectNode hint = JsonUtils.emptyObject();
        JsonNode tracks = goals.get("careTracksLabels");
        if (tracks != null && tracks.isArray() && !tracks.isEmpty()) {
            hint.set("careTracksLabels", tracks.deepCopy());
        }
        JsonNode appealLabels = goals.get("primaryAppealLabels");
        if (appealLabels != null && appealLabels.isArray() && !appealLabels.isEmpty()) {
            hint.set("primaryAppealLabels", appealLabels.deepCopy());
        } else {
            JsonNode appeal = goals.get("primaryAppealLabel");
            if (appeal != null && appeal.isTextual() && StringUtils.hasText(appeal.asText())) {
                hint.put("primaryAppealLabel", appeal.asText());
            }
        }
        boolean weight = CarePlanPromptContextSupport.includesWeightLossTrack(goals);
        hint.put("emphasizeWeightLoss", weight);
        if (!hint.isEmpty()) {
            payload.set("careFocus", hint);
        }
    }

    private static List<String> sanitizeDiseaseCodes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String code : raw) {
            if (CarePlanPromptContextSupport.isMeaningfulDiseaseCode(code)) {
                out.add(code);
            }
        }
        return out;
    }

    /** 快照里的 guideline 只有 code 列表，可引用条目单独以 guidelineRecs 给出。 */
    private static ObjectNode patientContext(CarePlanContext ctx) {
        if (ctx == null || ctx.snapshot() == null) {
            return JsonUtils.emptyObject();
        }
        ObjectNode copy = ctx.snapshot().deepCopy();
        copy.remove("guideline");
        return copy;
    }

    private static void putGuidelineRecs(ObjectNode payload, CarePlanContext ctx) {
        if (ctx == null || ctx.guidelines() == null || !ctx.guidelines().enabled()) {
            return;
        }
        payload.set("guidelineRecs", ctx.guidelines().toPrompt());
    }

    private static void putAllergyGuard(ObjectNode payload, CarePlanContext ctx) {
        if (ctx == null || ctx.foodAllergens() == null || ctx.foodAllergens().isEmpty()) {
            return;
        }
        payload.set("foodAllergyGuard", CarePlanAllergenGuard.promptGuard(ctx.foodAllergens()));
    }

    private String buildReviseUserMessage(
            CarePlanTemplateKeyEnum templateKey,
            CarePlanContext ctx,
            String instruction,
            List<String> diseaseCodes,
            JsonNode currentExercise,
            JsonNode currentDiet,
            JsonNode currentExecution,
            String currentGoalSummary) {
        ObjectNode payload = JsonUtils.emptyObject();
        payload.put("templateKey", templateKey.name());
        payload.put("mode", "REVISE");
        if (StringUtils.hasText(instruction)) {
            payload.put("revisionInstruction", instruction);
        }
        List<String> codes = sanitizeDiseaseCodes(diseaseCodes);
        if (!codes.isEmpty()) {
            payload.set("diseaseCodes", JsonUtils.mapper().valueToTree(codes));
        }
        payload.set("patientContext", patientContext(ctx));
        putAllergyGuard(payload, ctx);
        putGuidelineRecs(payload, ctx);
        putCareTrackHint(payload, ctx);
        putHighlightHint(payload, ctx);
        ObjectNode draft = JsonUtils.emptyObject();
        draft.set("exercise", currentExercise == null ? JsonUtils.emptyObject() : currentExercise);
        draft.set("diet", currentDiet == null ? JsonUtils.emptyObject() : currentDiet);
        draft.set("execution", currentExecution == null ? JsonUtils.emptyObject() : currentExecution);
        if (StringUtils.hasText(currentGoalSummary)) {
            draft.put("goalSummary", currentGoalSummary);
        }
        payload.set("currentDraft", draft);
        List<String> mustPreserve = extractMustPreserveConstraints(currentExercise, currentGoalSummary);
        if (!mustPreserve.isEmpty()) {
            payload.set("mustPreserveConstraints", JsonUtils.mapper().valueToTree(mustPreserve));
        }
        boolean dietFocused = looksDietFocusedRevision(instruction);
        if (dietFocused) {
            payload.put(
                    "revisionHint",
                    "本次意见偏饮食/消化：请主要修订 diet（及 summary/goalSummary 中相关表述），"
                            + "运动块保留 currentDraft 中的安全限制与活动强度，勿改回常规有氧/负重。");
        }
        return """
                请根据「修订意见」在「当前草稿」上修订，输出完整管理方案 JSON。
                若存在 mustPreserveConstraints，必须写入新方案的 exercise 与 goalSummary，除非修订意见明确解除。
                若有 patientContext.highlightGuidance，修订后仍须保留各条 mustApply（除非修订意见明确解除）。
                未要求改动的字段尽量保留原意与结构。
                字段顺序必须为：exercise → diet → execution → summary → goalSummary。
                仅输出 JSON，不要 markdown。

                %s
                """
                .formatted(JsonUtils.toJson(payload));
    }

    /** 从当前草稿抽出须跨轮保留的安全约束（骨折/制动等）。 */
    static List<String> extractMustPreserveConstraints(JsonNode exercise, String goalSummary) {
        List<String> out = new ArrayList<>();
        if (StringUtils.hasText(goalSummary) && looksLikeSafetyConstraint(goalSummary)) {
            out.add("goalSummary：" + goalSummary.trim());
        }
        if (exercise == null || exercise.isNull()) {
            return out;
        }
        String goal = textOrNull(exercise, "goal");
        if (StringUtils.hasText(goal) && looksLikeSafetyConstraint(goal)) {
            out.add("exercise.goal：" + goal.trim());
        }
        appendArrayConstraints(out, exercise.path("contraindications"), "contraindications");
        appendArrayConstraints(out, exercise.path("precautions"), "precautions");
        return out;
    }

    private static void appendArrayConstraints(List<String> out, JsonNode arr, String field) {
        if (arr == null || !arr.isArray()) {
            return;
        }
        for (JsonNode n : arr) {
            if (n != null && n.isTextual() && looksLikeSafetyConstraint(n.asText())) {
                out.add(field + "：" + n.asText().trim());
            }
        }
    }

    static boolean looksLikeSafetyConstraint(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String s = text.toLowerCase(Locale.ROOT);
        return containsAny(
                s,
                "骨折",
                "石膏",
                "夹板",
                "卧床",
                "制动",
                "负重",
                "禁止站立",
                "不可负重",
                "暂不",
                "床旁",
                "被动活动",
                "防血栓",
                "压疮",
                "医嘱不允许",
                "术后",
                "手术后",
                "打石膏");
    }

    static boolean looksDietFocusedRevision(String instruction) {
        if (!StringUtils.hasText(instruction)) {
            return false;
        }
        String s = instruction.toLowerCase(Locale.ROOT);
        boolean diet = containsAny(
                s, "饮食", "吃", "火锅", "拉肚子", "腹泻", "腹痛", "消化", "油腻", "少盐", "少油", "辣", "胃口", "胃");
        boolean clearsSafety = containsAny(s, "骨折已", "已愈合", "可以负重", "允许步行", "恢复步行", "取消制动");
        return diet && !clearsSafety;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String t = v.asText();
        return StringUtils.hasText(t) ? t.trim() : null;
    }

    private String loadSkillMarkdown() {
        try {
            Resource res = new ClassPathResource("skills/management-plan/SKILL.md", getClass().getClassLoader());
            if (res.exists()) {
                String raw =
                        StreamUtils.copyToString(res.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
                return SkillMarkdown.forLlm(raw);
            }
        } catch (Exception e) {
            log.debug("management-plan skill missing: {}", e.getMessage());
        }
        return "按患者档案生成管理方案（运动、饮食、执行计划与目标总结）。";
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s == null ? "" : s;
        }
        return s.substring(0, max) + "…";
    }
}
