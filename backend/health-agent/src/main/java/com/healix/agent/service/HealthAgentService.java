package com.healix.agent.service;

import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.memory.ChatMemoryStore;
import com.healix.agent.planner.IntentRouter;
import com.healix.agent.safety.SafetyValidator;
import com.healix.agent.support.AiUsageGuard;
import com.healix.agent.tool.AgentQuickAction;
import com.healix.agent.tool.HealthTools;
import com.healix.agent.tool.PatientBizTools;
import com.healix.common.domain.EntityMeta;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.common.constant.IntentType;
import com.healix.common.exception.BusinessException;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.identity.service.IdentityService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * C 端患者助手：意图路由 → 工具 → LLM/启发式回复 → 安全校验。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthAgentService {

    private final ChatMemoryStore chatMemoryStore;
    private final IntentRouter intentRouter;
    private final HealthTools healthTools;
    private final PatientBizTools patientBizTools;
    private final AiUsageGuard aiUsageGuard;
    private final SafetyValidator safetyValidator;
    private final AgentInteractionLogMapper interactionLogMapper;
    private final IdentityService identityService;
    private final LlmClient llmClient;

    @Value("${healix.agent.enabled:false}")
    private boolean agentLlmEnabled;

    @Transactional
    public AgentChatResponse chat(String patientId, String userMessage) {
        if (patientId == null) {
            throw new IllegalArgumentException("缺少患者 ID");
        }
        if (userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        PeopleProfile profile = identityService.requirePeople(patientId);
        if (profile.getTenantId() == null) {
            throw new BusinessException("请先加入机构后再使用 Agent");
        }
        String tenantId = profile.getTenantId();
        aiUsageGuard.check(tenantId, FeatureFlagKeyEnum.AI_PATIENT_AGENT, QuotaKeyEnum.AI_CALL_MONTHLY);

        IntentType intent = intentRouter.route(userMessage);
        List<String> memory = chatMemoryStore.asContextBlock(patientId);
        // RAG 未接入：知识块预留空列表
        List<String> knowledge = List.of();
        PatientBizTools.ToolOutput tools = invokeToolsIfNeeded(tenantId, patientId, intent);
        String toolResult = tools.text();
        String promptSnapshot = buildPromptSnapshot(userMessage, memory, knowledge, toolResult, intent);

        String draftReply;
        if (agentLlmEnabled && llmClient.isEnabled()) {
            String systemPrompt =
                    """
                    你是患者健康助手，提供生活方式与指标解读建议，不做医学诊断或处方。
                    回答简洁，使用中文，控制在 150 字内。若用户询问饮食/运动方案，结合可用数据给出建议。

                    下面是该患者当前的真实数据，回答时优先引用其中的具体数字，不要编造数据；
                    数据为空则直说暂无记录并建议去记录，不要臆测。
                    【患者数据】
                    %s
                    """
                            .formatted(toolResult.isBlank() ? "(暂无可用数据)" : toolResult);
            var history = memory.stream()
                    .map(s -> {
                        int sep = s.indexOf(" | A: ");
                        if (sep < 0) {
                            return new LlmClient.ChatTurn(s, "");
                        }
                        String user = s.startsWith("U: ") ? s.substring(3, sep) : s.substring(0, sep);
                        String assistant = s.substring(sep + 5);
                        return new LlmClient.ChatTurn(user, assistant);
                    })
                    .toList();
            LlmResponse llm = llmClient.chat(systemPrompt, userMessage, history);
            draftReply = llm.fromLlm() && llm.content() != null
                    ? llm.content()
                    : buildHeuristicReply(intent, toolResult, knowledge);
        } else {
            draftReply = buildHeuristicReply(intent, toolResult, knowledge);
        }

        String finalReply = safetyValidator.validate(tenantId, patientId, draftReply);
        persistLog(tenantId, patientId, intent, userMessage, promptSnapshot, toolResult, draftReply, finalReply);
        chatMemoryStore.appendTurn(patientId, userMessage, finalReply);
        return new AgentChatResponse(intent.name(), finalReply, tools.actions());
    }

    /**
     * 按意图组合业务只读工具。返回的文本进 LLM system prompt，动作回给 C 端渲染成按钮。
     *
     * <p>每个意图都带上今日方案/用药，患者最常问的「我今天还有什么要做」不必来回追问。
     */
    private PatientBizTools.ToolOutput invokeToolsIfNeeded(String tenantId, String patientId, IntentType intent) {
        List<PatientBizTools.ToolOutput> parts = new ArrayList<>();
        switch (intent) {
            case QUERY_PLAN -> {
                parts.add(patientBizTools.todayPlan(tenantId, patientId));
                parts.add(patientBizTools.medicationToday(tenantId, patientId));
            }
            case QUERY_MEDICATION -> parts.add(patientBizTools.medicationToday(tenantId, patientId));
            case QUERY_VITALS -> {
                parts.add(patientBizTools.recentMetrics(tenantId, patientId, 7));
                parts.add(patientBizTools.todayPlan(tenantId, patientId));
            }
            case QUERY_FOLLOWUP -> parts.add(patientBizTools.followups(tenantId, patientId));
            case QUERY_REPORT, WEEKLY_REPORT -> {
                parts.add(patientBizTools.latestReport(tenantId, patientId));
                parts.add(patientBizTools.recentMetrics(tenantId, patientId, 30));
            }
            case SET_REMINDER -> {
                parts.add(patientBizTools.todayPlan(tenantId, patientId));
                parts.add(patientBizTools.medicationToday(tenantId, patientId));
            }
            case DIET_PLAN -> {
                parts.add(new PatientBizTools.ToolOutput(healthTools.queryAllergens(patientId), List.of()));
                parts.add(patientBizTools.todayPlan(tenantId, patientId));
                parts.add(patientBizTools.recentMetrics(tenantId, patientId, 7));
            }
            case EXERCISE_PLAN -> {
                parts.add(patientBizTools.todayPlan(tenantId, patientId));
                parts.add(patientBizTools.recentMetrics(tenantId, patientId, 7));
            }
            default -> {
                parts.add(patientBizTools.todayPlan(tenantId, patientId));
                parts.add(patientBizTools.medicationToday(tenantId, patientId));
            }
        }
        return merge(parts);
    }

    private static PatientBizTools.ToolOutput merge(List<PatientBizTools.ToolOutput> parts) {
        List<String> texts = new ArrayList<>();
        List<AgentQuickAction> actions = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (PatientBizTools.ToolOutput part : parts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (StringUtils.hasText(part.text())) {
                texts.add(part.text());
            }
            for (AgentQuickAction action : part.actions()) {
                if (seenPaths.add(action.path())) {
                    actions.add(action);
                }
            }
        }
        // 按钮多了反而没人点，最多留 3 个
        if (actions.size() > 3) {
            actions = new ArrayList<>(actions.subList(0, 3));
        }
        return new PatientBizTools.ToolOutput(String.join("\n", texts), actions);
    }

    private String buildPromptSnapshot(
            String userMessage,
            List<String> memory,
            List<String> knowledge,
            String toolResult,
            IntentType intent) {
        String memoryBlock = memory.isEmpty() ? "(none)" : String.join("\n", memory);
        String knowledgeBlock =
                knowledge.isEmpty() ? "(none)" : knowledge.stream().collect(Collectors.joining("\n"));
        return """
                intent=%s
                memory:
                %s
                knowledge:
                %s
                tools:
                %s
                user:
                %s
                """.formatted(intent, memoryBlock, knowledgeBlock, toolResult, userMessage);
    }

    /** LLM 未启用时的兜底：直接把业务工具查到的数据念给患者，比报意图名有用。 */
    private String buildHeuristicReply(IntentType intent, String toolResult, List<String> knowledge) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(toolResult)) {
            sb.append(toolResult);
        } else {
            sb.append(switch (intent) {
                case QUERY_PLAN -> "还没查到今天的方案任务，去方案页看看吧。";
                case QUERY_MEDICATION -> "还没查到在用药记录，可以先去用药页添加。";
                case QUERY_FOLLOWUP -> "暂时没有待办随访，需要的话可以申请回访。";
                case QUERY_REPORT, WEEKLY_REPORT -> "还没有已发布的管理报告。";
                case QUERY_VITALS -> "近期还没有指标记录，记一次我就能帮你看趋势。";
                default -> "我在呢，想聊今天的方案、用药还是指标？";
            });
        }
        if (!knowledge.isEmpty()) {
            sb.append("\n参考：").append(knowledge.get(0));
        }
        return sb.toString();
    }

    private void persistLog(
            String tenantId,
            String patientId,
            IntentType intent,
            String userMessage,
            String promptSnapshot,
            String toolResult,
            String draftReply,
            String finalReply) {
        AgentInteractionLog logEntity = new AgentInteractionLog();
        logEntity.setAgentType(AgentTypeEnum.PATIENT.name());
        logEntity.setTenantId(tenantId);
        logEntity.setPeopleId(patientId);
        logEntity.setIntent(intent.name());
        logEntity.setUserMessage(userMessage);
        logEntity.setPromptSnapshot(promptSnapshot);
        logEntity.setToolCallsJson(toolResult);
        logEntity.setDraftReply(draftReply);
        logEntity.setFinalReply(finalReply);
        EntityMeta.onCreate(logEntity);
        interactionLogMapper.insert(logEntity);
    }

    public record AgentChatResponse(String intent, String reply, List<AgentQuickAction> actions) {
    }
}
