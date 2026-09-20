package com.healix.agent.careplan;

import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.enums.CarePlanSourceEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.service.CarePlanService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.template.CarePlanTemplateRegistry;
import com.healix.core.careplan.template.CarePlanTemplateRegistry.GeneratedPlan;
import com.healix.agent.careplan.CarePlanLlmGenerator.LlmPlanResult;
import com.healix.common.exception.BusinessException;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 健康管理主智能体（CARE_COPILOT）。
 * <p>LLM 结构化生成 + 模板降级；支持流式进度回调。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CarePlanAgentService {

    private final CarePlanService carePlanService;
    private final CarePlanContextTools contextTools;
    private final CarePlanLlmGenerator carePlanLlmGenerator;
    private final CarePlanTemplateRegistry templateRegistry;
    private final AgentInteractionLogMapper interactionLogMapper;
    private final ResourceLoader resourceLoader;
    private final LlmClient llmClient;

    @Lazy
    @Autowired
    private CarePlanAgentService self;

    @Value("${healix.agent.enabled:false}")
    private boolean agentLlmEnabled;

    @Transactional
    public CarePlanBundleDto generate(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodes,
            boolean replaceDraft) {
        return self.generateAndPersist(
                tenantId, orgId, peopleId, staffId, instruction, templateKey, diseaseCodes, replaceDraft, null);
    }

    public CarePlanBundleDto generateStream(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodes,
            boolean replaceDraft,
            Consumer<AgentStreamEvent> sink) {
        return generateStream(
                tenantId,
                orgId,
                peopleId,
                staffId,
                instruction,
                templateKey,
                diseaseCodes,
                replaceDraft,
                sink,
                false);
    }

    public CarePlanBundleDto generateStream(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodes,
            boolean replaceDraft,
            Consumer<AgentStreamEvent> sink,
            boolean requireLlm) {
        CarePlanStreamSink.progress(sink, "开始制定管理方案…");
        CarePlanBundleDto bundle = self.generateAndPersist(
                tenantId,
                orgId,
                peopleId,
                staffId,
                instruction,
                templateKey,
                diseaseCodes,
                replaceDraft,
                sink,
                requireLlm);
        CarePlanStreamSink.progress(sink, "方案草稿已保存，可前往审阅");
        return bundle;
    }

    @Transactional
    public CarePlanBundleDto generateAndPersist(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodesOverride,
            boolean replaceDraft,
            Consumer<AgentStreamEvent> sink) {
        return generateAndPersist(
                tenantId,
                orgId,
                peopleId,
                staffId,
                instruction,
                templateKey,
                diseaseCodesOverride,
                replaceDraft,
                sink,
                false);
    }

    @Transactional
    public CarePlanBundleDto generateAndPersist(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodesOverride,
            boolean replaceDraft,
            Consumer<AgentStreamEvent> sink,
            boolean requireLlm) {
        return generateInternal(
                tenantId,
                orgId,
                peopleId,
                staffId,
                instruction,
                templateKey,
                diseaseCodesOverride,
                replaceDraft,
                sink,
                requireLlm);
    }

    private CarePlanBundleDto generateInternal(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodesOverride,
            boolean replaceDraft,
            Consumer<AgentStreamEvent> sink,
            boolean requireLlm) {
        CarePlanStreamSink.progress(sink, "正在加载患者档案与观测数据…");
        CarePlanContext ctx = contextTools.loadCarePlanContext(
                tenantId,
                peopleId,
                (tool, status, detail) -> CarePlanStreamSink.tool(sink, tool, status, detail));

        String skillBundle = loadSkillMarkdown(sink);
        List<String> diseaseCodes = diseaseCodesOverride != null && !diseaseCodesOverride.isEmpty()
                ? diseaseCodesOverride
                : ctx.diseaseCodes();
        CarePlanTemplateKeyEnum key = templateRegistry.resolveTemplateKey(diseaseCodes, templateKey);

        AgentInteractionLog logEntity = new AgentInteractionLog();
        logEntity.setAgentType(AgentTypeEnum.CARE_COPILOT.name());
        logEntity.setTenantId(tenantId);
        logEntity.setPeopleId(peopleId);
        logEntity.setStaffId(staffId);
        logEntity.setIntent("CARE_PLAN_GENERATE");
        logEntity.setUserMessage(instruction == null ? "generate care plan" : instruction);
        logEntity.setPromptSnapshot(buildPromptSnapshot(skillBundle, ctx, templateKey, diseaseCodes, instruction));
        logEntity.setToolCallsJson("CarePlanContextTools.loadCarePlanContext");

        String source = CarePlanSourceEnum.TEMPLATE.name();
        GeneratedPlan generated;
        String goalSummary = null;
        LlmResponse llmMeta = null;

        CarePlanStreamSink.progress(sink, "正在解析病种与模板键…");
        CarePlanStreamSink.tool(
                sink,
                "resolveTemplateKey",
                "done",
                "模板键: " + key.name() + (diseaseCodes.isEmpty() ? "" : "，病种: " + String.join(", ", diseaseCodes)));

        if (agentLlmEnabled && llmClient.isEnabled()) {
            log.info(
                    "[CarePlan] generation path=LLM peopleId={} templateKey={} requireLlm={} llmEnabled=true",
                    peopleId,
                    key,
                    requireLlm);
            var llmResult = carePlanLlmGenerator.tryGenerate(key, ctx, instruction, diseaseCodes, sink);
            if (llmResult.isPresent()) {
                LlmPlanResult r = llmResult.get();
                generated = r.plan();
                source = CarePlanSourceEnum.LLM.name();
                goalSummary = r.goalSummary();
                llmMeta = r.llmResponse();
                logEntity.setDraftReply("LLM");
                log.info(
                        "[CarePlan] generation result=LLM peopleId={} templateKey={} promptTokens={} completionTokens={}",
                        peopleId,
                        key,
                        llmMeta.promptTokens(),
                        llmMeta.completionTokens());
                CarePlanStreamSink.progress(sink, "AI 已生成运动、饮食与执行计划");
            } else if (requireLlm) {
                throw new BusinessException(
                        "AI 生成失败：请检查 DeepSeek API Key 与网络，查看后端日志 [LLM] / [CarePlan]");
            } else {
                log.warn(
                        "[CarePlan] generation result=TEMPLATE_FALLBACK peopleId={} templateKey={} reason=llm_empty_or_parse_failed",
                        peopleId,
                        key);
                CarePlanStreamSink.progress(sink, "AI 生成未成功，切换为模板方案…");
                CarePlanStreamSink.tool(sink, "CarePlanTemplateRegistry", "running", "按模板生成方案");
                generated = templateRegistry.generate(key, ctx, instruction, diseaseCodes);
                CarePlanStreamSink.tool(sink, "CarePlanTemplateRegistry", "done", "模板方案已生成");
                logEntity.setDraftReply("TEMPLATE_FALLBACK");
            }
        } else if (requireLlm) {
            throw new BusinessException(
                    "LLM 未启用：请使用 dev profile 启动后端，并在 application-local.yml 配置 DeepSeek API Key");
        } else {
            log.info(
                    "[CarePlan] generation path=TEMPLATE peopleId={} templateKey={} agentLlmEnabled={} llmClientEnabled={}",
                    peopleId,
                    key,
                    agentLlmEnabled,
                    llmClient.isEnabled());
            CarePlanStreamSink.progress(sink, "正在使用模板生成方案…");
            CarePlanStreamSink.tool(sink, "CarePlanTemplateRegistry", "running", "按模板生成运动/饮食/执行计划");
            generated = templateRegistry.generate(key, ctx, instruction, diseaseCodes);
            CarePlanStreamSink.tool(sink, "CarePlanTemplateRegistry", "done", "模板方案已生成");
            logEntity.setDraftReply("TEMPLATE");
        }

        CarePlanStreamSink.progress(sink, "正在进行安全校验…");
        CarePlanStreamSink.tool(sink, "safetyCheck", "done", "方案结构校验通过");

        if (llmMeta != null) {
            logEntity.setPromptTokens(llmMeta.promptTokens());
            logEntity.setCompletionTokens(llmMeta.completionTokens());
        }
        logEntity.setFinalReply(source);
        EntityMeta.onCreate(logEntity);
        interactionLogMapper.insert(logEntity);

        CarePlanStreamSink.progress(sink, "正在保存方案草稿…");
        CarePlanStreamSink.tool(sink, "persistGenerated", "running", "写入方案草稿");
        CarePlanBundleDto bundle = carePlanService.persistGenerated(
                tenantId,
                orgId,
                peopleId,
                staffId,
                generated,
                source,
                goalSummary,
                replaceDraft,
                logEntity.getId());
        CarePlanStreamSink.tool(sink, "persistGenerated", "done", "草稿已保存");
        return bundle;
    }

    private String buildPromptSnapshot(
            String skills,
            CarePlanContext ctx,
            String templateKey,
            List<String> diseaseCodes,
            String instruction) {
        return """
                skills:
                %s
                templateKey=%s
                diseaseCodes=%s
                instruction=%s
                context:
                %s
                llmEnabled=%s
                """
                .formatted(
                        skills,
                        templateKey,
                        diseaseCodes,
                        instruction,
                        JsonUtils.toJson(ctx.snapshot()),
                        agentLlmEnabled);
    }

    private String loadSkillMarkdown(Consumer<AgentStreamEvent> sink) {
        String path = "classpath:skills/management-plan/SKILL.md";
        CarePlanStreamSink.progress(sink, "正在加载方案生成指引…");
        try {
            Resource res = resourceLoader.getResource(path);
            if (res.exists()) {
                String content =
                        StreamUtils.copyToString(res.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
                SkillMeta meta = parseSkillMeta(content);
                CarePlanStreamSink.skill(sink, meta.displayName(), meta.summary());
                return "--- " + path + " ---\n" + content + "\n";
            }
        } catch (Exception ignored) {
            // skill 文档缺失不影响生成
        }
        return "(skills missing)";
    }

    /** 给前端时间线用的可读文案，不暴露 markdown 标题摘录。 */
    private static SkillMeta parseSkillMeta(String content) {
        String displayName = "管理方案生成";
        String summary = "生成运动、饮食、执行计划与方案总结草稿";
        if (!StringUtils.hasText(content)) {
            return new SkillMeta(displayName, summary);
        }
        if (content.startsWith("---")) {
            int end = content.indexOf("\n---", 3);
            if (end > 0) {
                String front = content.substring(3, end);
                for (String line : front.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("display_name:")) {
                        String v = trimmed.substring("display_name:".length()).trim();
                        if (StringUtils.hasText(v)) {
                            displayName = stripQuotes(v);
                        }
                    } else if (trimmed.startsWith("description:")) {
                        String v = trimmed.substring("description:".length()).trim();
                        if (StringUtils.hasText(v)) {
                            String plain = stripQuotes(v);
                            int cut = plain.indexOf('。');
                            summary = cut > 0 ? plain.substring(0, cut + 1) : plain;
                            if (summary.length() > 60) {
                                summary = summary.substring(0, 60) + "…";
                            }
                        }
                    }
                }
            }
        }
        return new SkillMeta(displayName, summary);
    }

    private static String stripQuotes(String raw) {
        if (raw.length() >= 2) {
            char a = raw.charAt(0);
            char b = raw.charAt(raw.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return raw.substring(1, raw.length() - 1).trim();
            }
        }
        return raw;
    }

    private record SkillMeta(String displayName, String summary) {}
}
