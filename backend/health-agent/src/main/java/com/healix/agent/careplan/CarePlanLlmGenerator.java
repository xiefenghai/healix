package com.healix.agent.careplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
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
            Consumer<AgentStreamEvent> sink) {
        if (!llmClient.isEnabled()) {
            log.info("[CarePlan] LLM disabled, will use template fallback templateKey={}", templateKey);
            return Optional.empty();
        }
        log.info(
                "[CarePlan] LLM generate start templateKey={} diseaseCodes={} instructionChars={} contextIncomplete={}",
                templateKey,
                diseaseCodes == null || diseaseCodes.isEmpty() ? "[]" : diseaseCodes,
                instruction == null ? 0 : instruction.length(),
                ctx != null && ctx.contextIncomplete());
        CarePlanStreamSink.progress(sink, "正在加载方案生成指引…");
        String skill = loadSkillMarkdown();
        String userMessage = buildUserMessage(templateKey, ctx, instruction, diseaseCodes);
        CarePlanStreamSink.progress(sink, "正在调用 AI 生成运动、饮食与执行计划…");
        long thinkStarted = System.currentTimeMillis();
        CarePlanStreamSink.thinkingStart(sink, "规划运动、饮食与执行结构…");
        CarePlanStreamSink.tool(sink, "DeepSeek", "running", "正在生成管理方案…");
        CarePlanReadableStreamer readable = new CarePlanReadableStreamer(sink);
        LlmResponse llm = llmClient.streamChat(
                "CARE_PLAN_GENERATE",
                skill,
                userMessage,
                List.of(),
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
            Consumer<AgentStreamEvent> sink) {
        if (!llmClient.isEnabled()) {
            log.info("[CarePlan] LLM disabled, cannot revise templateKey={}", templateKey);
            return Optional.empty();
        }
        log.info(
                "[CarePlan] LLM revise start templateKey={} instructionChars={}",
                templateKey,
                instruction == null ? 0 : instruction.length());
        CarePlanStreamSink.progress(sink, "正在加载方案修订指引…");
        String skill = loadSkillMarkdown() + "\n\n## 修订模式补充（硬性）\n"
                + "1. 在 currentDraft 上按 revisionInstruction 局部调整；未提及部分保持原意与结构。\n"
                + "2. mustPreserveConstraints / 草稿中的骨折·卧床·制动·禁止负重等安全限制必须保留，"
                + "除非修订意见明确解除。\n"
                + "3. 饮食/消化类修订优先改 diet，禁止把运动从安全制动改回常规有氧。\n"
                + "4. goalSummary 须同时保留仍有效的安全限制与本次新增关注。\n"
                + "5. 仍输出完整 exercise/diet/execution/summary/goalSummary JSON。\n";
        String userMessage = buildReviseUserMessage(
                templateKey,
                ctx,
                instruction,
                diseaseCodes,
                currentExercise,
                currentDiet,
                currentExecution,
                currentGoalSummary);
        CarePlanStreamSink.progress(sink, "正在按你的要求修订方案草稿…");
        long thinkStarted = System.currentTimeMillis();
        CarePlanStreamSink.thinkingStart(sink, "对照草稿落实修订意见…");
        CarePlanStreamSink.tool(sink, "DeepSeek", "running", "正在修订管理方案…");
        CarePlanReadableStreamer readable = new CarePlanReadableStreamer(sink);
        LlmResponse llm = llmClient.streamChat(
                "CARE_PLAN_REVISE",
                skill,
                userMessage,
                List.of(),
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
            CarePlanStreamSink.tool(sink, "DeepSeek", "done", "未返回有效内容 · " + thinkMs + "ms");
            CarePlanStreamSink.thinkingDone(sink, "模型未返回有效内容", thinkMs);
            return Optional.empty();
        }
        CarePlanStreamSink.tool(
                sink,
                "DeepSeek",
                "done",
                (revise ? "修订草案已生成 · " : "方案草案已生成 · ") + thinkMs + "ms");
        CarePlanStreamSink.thinkingDone(
                sink, revise ? "已按意见整理方案" : "已整理运动、饮食与执行计划", thinkMs);
        CarePlanStreamSink.progress(sink, "正在校验方案结构…");
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

        GeneratedPlan plan = new GeneratedPlan(
                key, tags, exercise, diet, execution, snapshot, incomplete, specialtyMismatch, hypo);
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
        if (diseaseCodes != null && !diseaseCodes.isEmpty()) {
            payload.set("diseaseCodes", JsonUtils.mapper().valueToTree(diseaseCodes));
        }
        payload.set("patientContext", ctx == null ? JsonUtils.emptyObject() : ctx.snapshot());
        putAllergyGuard(payload, ctx);
        return """
                请根据以下输入生成完整管理方案 JSON。
                字段顺序必须为：exercise → diet → execution → summary → goalSummary。
                仅输出 JSON，不要 markdown。

                %s
                """
                .formatted(JsonUtils.toJson(payload));
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
        if (diseaseCodes != null && !diseaseCodes.isEmpty()) {
            payload.set("diseaseCodes", JsonUtils.mapper().valueToTree(diseaseCodes));
        }
        payload.set("patientContext", ctx == null ? JsonUtils.emptyObject() : ctx.snapshot());
        putAllergyGuard(payload, ctx);
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
                return StreamUtils.copyToString(res.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
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
