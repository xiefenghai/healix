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
        CarePlanContext patientCtx = contextService.load(cmd.tenantId(), cmd.peopleId());
        String systemPrompt = buildSystemPrompt(ctx.patientDisplayName(), patientCtx);

        String reply;
        if (sink != null && llmClient.isEnabled()) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在思考…"));
            var llm = llmClient.streamChat(
                    "GENERAL_CHAT",
                    systemPrompt,
                    cmd.message(),
                    ctx.history(),
                    token -> AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(token)));
            reply = llm.fromLlm() && StringUtils.hasText(llm.content()) ? llm.content() : null;
        } else {
            var llm = llmClient.chat("GENERAL_CHAT", systemPrompt, cmd.message(), ctx.history());
            reply = llm.fromLlm() && StringUtils.hasText(llm.content()) ? llm.content() : fallbackReply();
        }

        if (!StringUtils.hasText(reply)) {
            reply = fallbackReply();
        }
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.GENERAL_CHAT.name(),
                "GENERAL_CHAT",
                reply,
                List.of(),
                List.of(),
                null);
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

    private static String fallbackReply() {
        return "灵犀暂时不可用（LLM 未启用或调用失败）。您可以尝试使用「制定管理方案」快捷能力。";
    }
}
