package com.healix.agent.handler;

import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.llm.LlmClient.ChatTurn;
import java.util.List;

/** 单次能力执行上下文（由 {@code StaffAgentTools} 从 TurnContext 构造）。 */
public record AgentHandlerContext(
        AgentChatCommand command, String sessionId, List<ChatTurn> history, String patientDisplayName) {}
