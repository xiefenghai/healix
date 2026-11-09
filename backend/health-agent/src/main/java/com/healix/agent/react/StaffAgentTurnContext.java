package com.healix.agent.react;

import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient.ChatTurn;
import com.healix.agent.handler.AgentHandlerContext;
import com.healix.agent.stream.AgentStreamEvent;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.util.StringUtils;

/**
 * 单次驾驶舱请求的线程上下文：供 {@code @Tool} 读取会话，并暂存结构化 {@link AgentResponse}（卡片契约）。
 */
public final class StaffAgentTurnContext {

    private static final ThreadLocal<StaffAgentTurnContext> HOLDER = new ThreadLocal<>();

    private final AgentChatCommand command;
    private final String sessionId;
    private final List<ChatTurn> history;
    private final String patientDisplayName;
    private final Consumer<AgentStreamEvent> sink;
    private AgentResponse lastPrimary;
    private AgentResponse lastAny;

    private StaffAgentTurnContext(
            AgentChatCommand command,
            String sessionId,
            List<ChatTurn> history,
            String patientDisplayName,
            Consumer<AgentStreamEvent> sink) {
        this.command = command;
        this.sessionId = sessionId;
        this.history = history == null ? List.of() : history;
        this.patientDisplayName = patientDisplayName;
        this.sink = sink;
    }

    public static void open(
            AgentChatCommand command,
            String sessionId,
            List<ChatTurn> history,
            String patientDisplayName,
            Consumer<AgentStreamEvent> sink) {
        HOLDER.set(new StaffAgentTurnContext(command, sessionId, history, patientDisplayName, sink));
    }

    public static void close() {
        HOLDER.remove();
    }

    public static StaffAgentTurnContext require() {
        StaffAgentTurnContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException("StaffAgentTurnContext missing");
        }
        return ctx;
    }

    public static StaffAgentTurnContext current() {
        return HOLDER.get();
    }

    public AgentHandlerContext handlerContext() {
        return new AgentHandlerContext(command, sessionId, history, patientDisplayName);
    }

    public AgentChatCommand command() {
        return command;
    }

    public String sessionId() {
        return sessionId;
    }

    public Consumer<AgentStreamEvent> sink() {
        return sink;
    }

    public void record(AgentResponse response) {
        if (response == null) {
            return;
        }
        lastAny = response;
        if (isPrimaryCapability(response.capability())) {
            lastPrimary = response;
        }
    }

    public AgentResponse consumeStructured() {
        return lastPrimary != null ? lastPrimary : lastAny;
    }

    private static boolean isPrimaryCapability(String capability) {
        if (!StringUtils.hasText(capability)) {
            return false;
        }
        try {
            AgentCapability cap = AgentCapability.valueOf(capability.trim());
            return cap != AgentCapability.GENERAL_CHAT;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
