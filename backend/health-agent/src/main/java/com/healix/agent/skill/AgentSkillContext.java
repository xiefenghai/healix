package com.healix.agent.skill;

import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.llm.LlmClient.ChatTurn;
import java.util.List;

public record AgentSkillContext(
        AgentChatCommand command, String sessionId, List<ChatTurn> history, String patientDisplayName) {}
