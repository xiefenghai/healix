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
        ChatExtractCard card = cmd.intakeFields() != null && !cmd.intakeFields().isEmpty()
                ? chatExtractService.extractIntake(cmd.peopleId(), cmd.message(), cmd.intakeFields())
                : chatExtractService.extract(cmd.tenantId(), cmd.peopleId(), cmd.message());
        int count = card.getItems() == null ? 0 : (int) card.getItems().stream()
                .filter(row -> row.getOutcome() == null || row.getOutcome().isBlank())
                .count();
        String preview = count > 0
                ? (card.isIntake() ? "提取到 " + count + " 项，请打开右侧核对" : "提取到 " + count + " 项，请在卡片里核对")
                : "这段话里没有对上可写入的内容";
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
