package com.healix.agent.service;

import com.healix.agent.log.AgentInteractionLog;
import com.healix.agent.log.AgentInteractionLogMapper;
import com.healix.agent.memory.ChatMemoryStore;
import com.healix.agent.planner.IntentRouter;
import com.healix.agent.rag.KnowledgeRetriever;
import com.healix.agent.safety.SafetyValidator;
import com.healix.agent.tool.HealthTools;
import com.healix.common.domain.EntityMeta;
import com.healix.core.agent.enums.AgentTypeEnum;
import com.healix.common.constant.IntentType;
import com.healix.common.exception.BusinessException;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.identity.service.IdentityService;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthAgentService {

    private final ChatMemoryStore chatMemoryStore;
    private final KnowledgeRetriever knowledgeRetriever;
    private final IntentRouter intentRouter;
    private final HealthTools healthTools;
    private final SafetyValidator safetyValidator;
    private final AgentInteractionLogMapper interactionLogMapper;
    private final IdentityService identityService;

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

        IntentType intent = intentRouter.route(userMessage);
        List<String> memory = chatMemoryStore.asContextBlock(patientId);
        List<String> knowledge = knowledgeRetriever.retrieve(userMessage, 5);
        String toolResult = invokeToolsIfNeeded(tenantId, patientId, intent);
        String promptSnapshot = buildPromptSnapshot(userMessage, memory, knowledge, toolResult, intent);

        String draftReply;
        if (agentLlmEnabled) {
            draftReply = "[LLM placeholder] " + promptSnapshot;
            log.info("LLM path enabled but not fully wired; returning placeholder");
        } else {
            draftReply = buildHeuristicReply(intent, toolResult, knowledge);
        }

        String finalReply = safetyValidator.validate(tenantId, patientId, draftReply);
        persistLog(tenantId, patientId, intent, userMessage, promptSnapshot, toolResult, draftReply, finalReply);
        chatMemoryStore.appendTurn(patientId, userMessage, finalReply);
        return new AgentChatResponse(intent.name(), finalReply);
    }

    private String invokeToolsIfNeeded(String tenantId, String patientId, IntentType intent) {
        return switch (intent) {
            case QUERY_VITALS ->
                    healthTools.queryDailyStep(tenantId, patientId)
                            + "; "
                            + healthTools.queryGlucoseAverage(tenantId, patientId, 7);
            case DIET_PLAN -> healthTools.queryAllergens(patientId);
            case EXERCISE_PLAN -> healthTools.queryGlucoseAverage(tenantId, patientId, 7);
            default -> "";
        };
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

    private String buildHeuristicReply(IntentType intent, String toolResult, List<String> knowledge) {
        StringBuilder sb = new StringBuilder();
        sb.append("已识别意图：").append(intent.name()).append("。");
        if (toolResult != null && !toolResult.isBlank()) {
            sb.append(" 数据：").append(toolResult).append("。");
        }
        if (!knowledge.isEmpty()) {
            sb.append(" 参考知识：").append(knowledge.get(0));
        } else {
            sb.append(" （开发模式：LLM 未启用，返回启发式回复）");
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

    public record AgentChatResponse(String intent, String reply) {
    }
}
