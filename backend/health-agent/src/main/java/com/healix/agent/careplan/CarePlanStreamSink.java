package com.healix.agent.careplan;

import com.healix.agent.stream.AgentStreamEvent;
import java.util.function.Consumer;

final class CarePlanStreamSink {

    private CarePlanStreamSink() {}

    static void progress(Consumer<AgentStreamEvent> sink, String message) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress(message));
    }

    static void tool(Consumer<AgentStreamEvent> sink, String name, String status, String detail) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool(name, status, detail));
    }

    static void skill(Consumer<AgentStreamEvent> sink, String name, String detail) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.skill(name, detail));
    }

    static void thinkingStart(Consumer<AgentStreamEvent> sink, String text) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingStart(text));
    }

    static void thinkingDone(Consumer<AgentStreamEvent> sink, String text, long elapsedMs) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingDone(text, elapsedMs));
    }

    static void token(Consumer<AgentStreamEvent> sink, String text) {
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(text));
    }
}
