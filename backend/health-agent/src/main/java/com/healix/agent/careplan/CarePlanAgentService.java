package com.healix.agent.careplan;

import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.domain.EntityMeta;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.support.CarePlanEnergyTargetService;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.enums.CarePlanSourceEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.service.CarePlanService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanPromptContextSupport;
import com.healix.core.careplan.template.CarePlanTemplateRegistry;
import com.healix.core.careplan.template.CarePlanTemplateRegistry.GeneratedPlan;
import com.healix.agent.careplan.CarePlanLlmGenerator.LlmPlanResult;
import com.healix.agent.support.SkillMarkdown;
import com.healix.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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
                tenantId,
                orgId,
                peopleId,
                staffId,
                instruction,
                templateKey,
                diseaseCodes,
                replaceDraft,
                null,
                false,
                List.of());
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
                false,
                List.of());
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
                requireLlm,
                List.of());
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
            boolean requireLlm,
            List<ChatTurn> history) {
        CarePlanStreamSink.progress(sink, "开始为患者定制管理方案…");
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
                requireLlm,
                history);
        CarePlanStreamSink.progress(sink, "方案草稿已就绪，可打开编辑器精修");
        return bundle;
    }

    /**
     * 多轮修订：在当前草稿上按指令更新；无草稿时回退为重新生成。
     */
    public CarePlanBundleDto reviseStream(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            Consumer<AgentStreamEvent> sink) {
        return reviseStream(tenantId, orgId, peopleId, staffId, instruction, sink, List.of());
    }

    public CarePlanBundleDto reviseStream(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            Consumer<AgentStreamEvent> sink,
            List<ChatTurn> history) {
        CarePlanStreamSink.progress(sink, "开始按你的意见修订方案草稿…");
        CarePlanBundleDto current = carePlanService.getBundle(tenantId, orgId, peopleId);
        if (current == null || current.getDraft() == null) {
            CarePlanStreamSink.progress(sink, "当前没有草稿，改为重新生成方案…");
            return generateStream(
                    tenantId, orgId, peopleId, staffId, instruction, null, null, true, sink, true, history);
        }
        CarePlanBundleDto bundle =
                self.reviseAndPersist(tenantId, orgId, peopleId, staffId, instruction, sink, history);
        CarePlanStreamSink.progress(sink, "方案草稿已更新，可打开编辑器精修");
        return bundle;
    }

    @Transactional
    public CarePlanBundleDto reviseAndPersist(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            Consumer<AgentStreamEvent> sink,
            List<ChatTurn> history) {
        CarePlanBundleDto current = carePlanService.getBundle(tenantId, orgId, peopleId);
        if (current == null || current.getDraft() == null) {
            throw new BusinessException("无草稿可修订，请先生成管理方案");
        }
        var draft = current.getDraft();
        int expectedVersion = draft.getVersion() == null ? 1 : draft.getVersion();

        CarePlanStreamSink.progress(sink, "正在读取患者档案与化验指标…");
        CarePlanContext ctx = contextTools.loadCarePlanContext(
                tenantId,
                peopleId,
                (tool, status, detail) -> CarePlanStreamSink.tool(sink, tool, status, detail));
        List<String> diseaseCodes = sanitizeDiseaseCodes(ctx.diseaseCodes());
        CarePlanTemplateKeyEnum key = templateRegistry.resolveTemplateKey(diseaseCodes, null);

        AgentInteractionLog logEntity = new AgentInteractionLog();
        logEntity.setAgentType(AgentTypeEnum.CARE_COPILOT.name());
        logEntity.setTenantId(tenantId);
        logEntity.setPeopleId(peopleId);
        logEntity.setStaffId(staffId);
        logEntity.setIntent("CARE_PLAN_REVISE");
        logEntity.setUserMessage(instruction == null ? "revise care plan" : instruction);
        logEntity.setToolCallsJson("CarePlanContextTools.loadCarePlanContext");

        if (!agentLlmEnabled || !llmClient.isEnabled()) {
            throw new BusinessException(
                    "LLM 未启用：请使用 dev profile 启动后端，并在 application-local.yml 配置 DeepSeek API Key");
        }

        String goalFromPlan =
                current.getPlan() != null ? current.getPlan().getGoalSummary() : null;
        var llmResult = carePlanLlmGenerator.tryRevise(
                key,
                ctx,
                instruction,
                diseaseCodes,
                draft.getExercise(),
                draft.getDiet(),
                draft.getExecution(),
                goalFromPlan,
                history,
                sink);
        if (llmResult.isEmpty()) {
            throw new BusinessException("AI 修订失败：请换一种说法重试，或打开方案页手工编辑");
        }
        LlmPlanResult r = llmResult.get();
        guardAllergens(ctx, r.plan().diet(), sink);
        keepEnergyTarget(draft.getDiet(), r.plan().diet());
        logEntity.setDraftReply("LLM_REVISE");
        if (r.llmResponse() != null) {
            logEntity.setPromptTokens(r.llmResponse().promptTokens());
            logEntity.setCompletionTokens(r.llmResponse().completionTokens());
        }
        logEntity.setFinalReply("LLM_REVISE");
        EntityMeta.onCreate(logEntity);
        interactionLogMapper.insert(logEntity);

        CarePlanStreamSink.progress(sink, "正在写入修订后的方案草稿…");
        CarePlanStreamSink.tool(sink, "保存草稿", "running", "写入方案草稿");
        CarePlanBundleDto bundle = carePlanService.updateDraft(
                tenantId,
                orgId,
                peopleId,
                staffId,
                expectedVersion,
                r.plan().exercise(),
                r.plan().diet(),
                r.plan().execution(),
                null,
                r.goalSummary(),
                r.summary());
        CarePlanStreamSink.tool(sink, "保存草稿", "done", "草稿已更新");
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
                false,
                List.of());
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
            boolean requireLlm,
            List<ChatTurn> history) {
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
                requireLlm,
                history);
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
            boolean requireLlm,
            List<ChatTurn> history) {
        CarePlanStreamSink.progress(sink, "正在读取患者档案与化验指标…");
        CarePlanContext ctx = contextTools.loadCarePlanContext(
                tenantId,
                peopleId,
                (tool, status, detail) -> CarePlanStreamSink.tool(sink, tool, status, detail));

        String skillBundle = loadSkillMarkdown(sink);
        List<String> diseaseCodes = sanitizeDiseaseCodes(
                diseaseCodesOverride != null && !diseaseCodesOverride.isEmpty()
                        ? diseaseCodesOverride
                        : ctx.diseaseCodes());
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

        CarePlanStreamSink.progress(sink, "正在匹配适宜的管理方向…");
        CarePlanStreamSink.tool(
                sink,
                "匹配管理方向",
                "done",
                diseaseCodes.isEmpty() ? "已选定综合管理路径" : "已结合病种匹配管理路径");

        if (agentLlmEnabled && llmClient.isEnabled()) {
            log.info(
                    "[CarePlan] generation path=LLM peopleId={} templateKey={} requireLlm={} llmEnabled=true",
                    peopleId,
                    key,
                    requireLlm);
            var llmResult =
                    carePlanLlmGenerator.tryGenerate(key, ctx, instruction, diseaseCodes, history, sink);
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
                CarePlanStreamSink.progress(sink, "运动、饮食与执行计划草案已形成");
            } else if (requireLlm) {
                throw new BusinessException(
                        "AI 生成失败：请检查 DeepSeek API Key 与网络，查看后端日志 [LLM] / [CarePlan]");
            } else {
                log.warn(
                        "[CarePlan] generation result=TEMPLATE_FALLBACK peopleId={} templateKey={} reason=llm_empty_or_parse_failed",
                        peopleId,
                        key);
                CarePlanStreamSink.progress(sink, "智能起草未完成，改用标准模板方案…");
                CarePlanStreamSink.tool(sink, "标准模板方案", "running", "按模板生成方案");
                generated = templateRegistry.generate(key, ctx, instruction, diseaseCodes);
                CarePlanStreamSink.tool(sink, "标准模板方案", "done", "模板方案已生成");
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
            CarePlanStreamSink.progress(sink, "正在按标准模板生成方案…");
            CarePlanStreamSink.tool(sink, "标准模板方案", "running", "按模板生成运动、饮食与执行计划");
            generated = templateRegistry.generate(key, ctx, instruction, diseaseCodes);
            CarePlanStreamSink.tool(sink, "标准模板方案", "done", "模板方案已生成");
            logEntity.setDraftReply("TEMPLATE");
        }

        CarePlanStreamSink.progress(sink, "正在做发布前安全校验…");
        guardAllergens(ctx, generated.diet(), sink);
        CarePlanStreamSink.tool(sink, "安全校验", "done", "方案结构与过敏规避已核对");

        if (llmMeta != null) {
            logEntity.setPromptTokens(llmMeta.promptTokens());
            logEntity.setCompletionTokens(llmMeta.completionTokens());
        }
        logEntity.setFinalReply(source);
        EntityMeta.onCreate(logEntity);
        interactionLogMapper.insert(logEntity);

        CarePlanStreamSink.progress(sink, "正在写入方案草稿…");
        CarePlanStreamSink.tool(sink, "保存草稿", "running", "写入方案草稿");
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
        CarePlanStreamSink.tool(sink, "保存草稿", "done", "草稿已保存");
        return bundle;
    }

    /** 修订不改热量目标：沿用草稿里的（含健管师手改值），保存时再按饮食目标刷新。 */
    private static void keepEnergyTarget(JsonNode currentDiet, ObjectNode revisedDiet) {
        if (revisedDiet == null) {
            return;
        }
        JsonNode target = currentDiet == null ? null : currentDiet.get(CarePlanEnergyTargetService.FIELD);
        if (target != null && target.isObject()) {
            revisedDiet.set(CarePlanEnergyTargetService.FIELD, target.deepCopy());
        } else {
            revisedDiet.remove(CarePlanEnergyTargetService.FIELD);
        }
    }

    /** 按过敏史补齐回避清单，并移除模型误推荐的过敏食物（服务端兜底，不依赖模型自觉）。 */
    private static void guardAllergens(CarePlanContext ctx, ObjectNode diet, Consumer<AgentStreamEvent> sink) {
        List<FoodAllergen> allergens = ctx.foodAllergens();
        CarePlanAllergenGuard.enforce(diet, allergens);
        List<String> removed = CarePlanAllergenGuard.sanitize(diet, allergens);
        if (allergens.isEmpty()) {
            CarePlanStreamSink.tool(sink, "过敏规避核对", "done", "暂无食物过敏史记录");
        } else if (removed.isEmpty()) {
            CarePlanStreamSink.tool(sink, "过敏规避核对", "done", "饮食建议与过敏史无冲突");
        } else {
            log.info("[CarePlan] AI diet allergen conflicts removed: {}", removed);
            CarePlanStreamSink.tool(
                    sink, "过敏规避核对", "done", "已剔除与过敏史冲突的推荐：" + String.join("、", removed));
        }
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

    private String loadSkillMarkdown(Consumer<AgentStreamEvent> sink) {
        String path = "classpath:skills/management-plan/SKILL.md";
        CarePlanStreamSink.progress(sink, "正在翻阅方案生成指引与临床共识…");
        try {
            Resource res = new ClassPathResource("skills/management-plan/SKILL.md", getClass().getClassLoader());
            if (res.exists()) {
                String content =
                        StreamUtils.copyToString(res.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
                SkillMeta meta = parseSkillMeta(content);
                CarePlanStreamSink.skill(sink, meta.displayName(), meta.summary());
                // 快照与模型一致：剥掉 Cursor YAML frontmatter
                return "--- " + path + " ---\n" + SkillMarkdown.forLlm(content) + "\n";
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
