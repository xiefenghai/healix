package com.healix.agent.skill;

import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 通用闲聊 / 驾驶舱对话：有 peopleId 时带档案上下文；无 peopleId 为机构级会话（COCKPIT_ORG_CHAT）。
 */
@Component
@RequiredArgsConstructor
public class GeneralChatSkill implements AgentSkill {

    private final LlmClient llmClient;
    private final CarePlanContextService contextService;

    @Override
    public AgentCapability capability() {
        return AgentCapability.GENERAL_CHAT;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.GENERAL_CHAT;
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        return executeStream(ctx, null);
    }

    public AgentResponse executeStream(AgentSkillContext ctx, Consumer<AgentStreamEvent> sink) {
        AgentChatCommand cmd = ctx.command();
        boolean orgSession = !StringUtils.hasText(cmd.peopleId());
        String systemPrompt;
        if (orgSession) {
            systemPrompt = buildOrgSystemPrompt();
        } else {
            CarePlanContext patientCtx = contextService.load(cmd.tenantId(), cmd.peopleId());
            systemPrompt = buildSystemPrompt(ctx.patientDisplayName(), patientCtx);
        }

        String reply;
        if (sink != null && llmClient.isEnabled()) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在思考…"));
            var llm = llmClient.streamChat(
                    orgSession ? "COCKPIT_ORG_CHAT" : "GENERAL_CHAT",
                    systemPrompt,
                    cmd.message(),
                    ctx.history(),
                    token -> AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(token)));
            reply = llm.fromLlm() && StringUtils.hasText(llm.content()) ? llm.content() : null;
        } else {
            var llm = llmClient.chat(
                    orgSession ? "COCKPIT_ORG_CHAT" : "GENERAL_CHAT",
                    systemPrompt,
                    cmd.message(),
                    ctx.history());
            reply = llm.fromLlm() && StringUtils.hasText(llm.content()) ? llm.content() : fallbackReply(orgSession);
        }

        if (!StringUtils.hasText(reply)) {
            reply = fallbackReply(orgSession);
        }
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.GENERAL_CHAT.name(),
                orgSession ? "ORG_CHAT" : "GENERAL_CHAT",
                reply,
                List.of(),
                List.of(),
                null);
    }

    private static String buildOrgSystemPrompt() {
        return """
                你是 Healix 灵犀，健管师机构工作台（智能驾驶舱）的 AI 协作者。
                当前处于机构级会话（未绑定具体患者）。
                规则：
                1. 协助安排今日优先、解释任务类型与依从红灯含义；不做诊断或处方。
                2. 需要针对某位患者深入处理时，请提示用户在左侧「今日优先」点选该患者。
                3. 回答简洁、结构化，使用中文。
                """;
    }

    private static String buildSystemPrompt(String displayName, CarePlanContext patientCtx) {
        return """
                你是 Healix 灵犀（CARE_COPILOT），健管师工作台的 AI 协作者，协助医护人员管理患者健康。
                规则：
                1. 仅提供健康管理建议，不做医学诊断或处方。
                2. 可解读指标趋势、生活方式建议；涉及完整方案生成请提示使用「制定管理方案」。
                3. 回答简洁、结构化，使用中文。

                当前患者：%s
                上下文摘要：
                %s
                """
                .formatted(displayName, JsonUtils.toJson(patientCtx.snapshot()));
    }

    private static String fallbackReply(boolean orgSession) {
        if (orgSession) {
            return "灵犀暂时不可用。请先查看左侧今日优先名单，或点击「刷新今日建议」。";
        }
        return "灵犀暂时不可用（LLM 未启用或调用失败）。您可以尝试使用「制定管理方案」快捷能力。";
    }
}
