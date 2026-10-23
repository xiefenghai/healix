package com.healix.agent.skill;

import com.healix.agent.extract.ChatExtractCard;
import com.healix.agent.extract.ChatExtractService;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatExtractSkill implements AgentSkill {

    private final ChatExtractService chatExtractService;

    @Override
    public AgentCapability capability() {
        return AgentCapability.CHAT_EXTRACT;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.CHAT_EXTRACT;
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        AgentChatCommand cmd = ctx.command();
        ChatExtractCard card = chatExtractService.extract(cmd.tenantId(), cmd.peopleId(), cmd.message());
        int chars = cmd.message() == null ? 0 : cmd.message().trim().length();
        String preview = "已整理一段记录，共 " + chars + " 字";
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.CHAT_EXTRACT.name(),
                "CHAT_EXTRACT",
                preview,
                List.of(),
                List.of(),
                card);
    }
}
