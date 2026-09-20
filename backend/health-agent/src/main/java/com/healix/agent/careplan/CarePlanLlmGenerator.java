package com.healix.agent.careplan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.template.CarePlanTemplateRegistry.GeneratedPlan;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
public class CarePlanLlmGenerator {

    private final LlmClient llmClient;
    private final ResourceLoader resourceLoader;

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
        long thinkMs = System.currentTimeMillis() - thinkStarted;
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            log.warn(
                    "[CarePlan] LLM empty response, fallback to template templateKey={}",
                    templateKey);
            CarePlanStreamSink.tool(sink, "DeepSeek", "done", "未返回有效内容 · " + thinkMs + "ms");
            CarePlanStreamSink.thinkingDone(sink, "模型未返回有效内容", thinkMs);
            return Optional.empty();
        }
        CarePlanStreamSink.tool(sink, "DeepSeek", "done", "方案草案已生成 · " + thinkMs + "ms");
        CarePlanStreamSink.thinkingDone(sink, "已整理运动、饮食与执行计划", thinkMs);
        CarePlanStreamSink.progress(sink, "正在校验方案结构…");
        try {
            LlmPlanResult result = parseLlmOutput(llm, templateKey, ctx, diseaseCodes);
            log.info(
                    "[CarePlan] LLM parse success templateKey={} goalSummary={} responseChars={} promptTokens={} completionTokens={}",
                    templateKey,
                    result.goalSummary(),
                    llm.content().length(),
                    llm.promptTokens(),
                    llm.completionTokens());
            if (StringUtils.hasText(result.goalSummary())) {
                CarePlanStreamSink.progress(sink, "目标：" + truncate(result.goalSummary(), 48));
            }
            return Optional.of(result);
        } catch (Exception e) {
            log.warn(
                    "[CarePlan] LLM parse failed, fallback to template templateKey={} error={}",
                    templateKey,
                    e.getMessage());
            CarePlanStreamSink.tool(sink, "parseCarePlan", "done", "结构校验未通过，改用模板方案");
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

        boolean noDisease = templateKey == CarePlanTemplateKeyEnum.GENERAL;
        boolean hypo = ctx != null && ctx.hypoglycemiaRisk();
        boolean incomplete = ctx == null || ctx.contextIncomplete();
        List<String> tags = diseaseCodes != null && !diseaseCodes.isEmpty()
                ? diseaseCodes
                : (ctx == null ? List.of() : ctx.diseaseCodes());

        ObjectNode snapshot = ctx != null && ctx.snapshot() != null
                ? ctx.snapshot().deepCopy()
                : JsonUtils.emptyObject();
        snapshot.put("templateKey", templateKey.name());
        snapshot.put("contextIncomplete", incomplete);
        snapshot.put("hypoglycemiaRisk", hypo);
        snapshot.put("generationMode", "LLM");
        if (StringUtils.hasText(summary)) {
            snapshot.put("summary", summary);
        }

        GeneratedPlan plan = new GeneratedPlan(
                templateKey, tags, exercise, diet, execution, snapshot, incomplete, noDisease, hypo);
        return new LlmPlanResult(plan, summary, goalSummary, llm);
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
        try {
            return JsonUtils.mapper().readTree(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid care plan JSON", e);
        }
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
        return """
                请根据以下输入生成完整管理方案 JSON。
                字段顺序必须为：exercise → diet → execution → summary → goalSummary。
                仅输出 JSON，不要 markdown。

                %s
                """
                .formatted(JsonUtils.toJson(payload));
    }

    private String loadSkillMarkdown() {
        try {
            Resource res = resourceLoader.getResource("classpath:skills/management-plan/SKILL.md");
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
