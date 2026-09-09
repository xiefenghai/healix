package com.healix.agent.skill;

import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OcrExamSkill implements AgentSkill {

    @Override
    public AgentCapability capability() {
        return AgentCapability.OCR_EXAM;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        return routed == AgentCapability.OCR_EXAM;
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        String path = "/workspace/patients/" + ctx.command().peopleId() + "/observations/exams";
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.OCR_EXAM.name(),
                "OCR_EXAM",
                "检查单 OCR 即将上线。您可先前往检查录入页手工录入。",
                List.of(com.healix.agent.gateway.AgentAction.navigate("前往检查录入", path)),
                List.of(),
                null);
    }

    static boolean matchesMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return msg.contains("检查单") || msg.contains("影像") || msg.contains("exam");
    }
}
