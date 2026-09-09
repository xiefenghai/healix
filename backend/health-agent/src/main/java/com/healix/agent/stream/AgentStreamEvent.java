package com.healix.agent.stream;

import com.healix.common.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** SSE 事件：type + JSON payload */
public record AgentStreamEvent(String type, Map<String, Object> data) {

    public static AgentStreamEvent progress(String message) {
        return of("progress", Map.of("message", message));
    }

    public static AgentStreamEvent tool(String name, String status, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("status", status);
        data.put("detail", detail);
        return of("tool", data);
    }

    public static AgentStreamEvent skill(String name, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("detail", detail);
        return of("skill", data);
    }

    public static AgentStreamEvent token(String text) {
        return of("token", Map.of("text", text));
    }

    public static AgentStreamEvent result(Object payload) {
        return of("result", Map.of("payload", payload));
    }

    public static AgentStreamEvent error(String message) {
        return of("error", Map.of("message", message));
    }

    public static AgentStreamEvent done() {
        return of("done", Map.of());
    }

    public static AgentStreamEvent of(String type, Map<String, Object> data) {
        return new AgentStreamEvent(type, data);
    }

    public String toJsonLine() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("data", data);
        return JsonUtils.toJson(envelope);
    }

    public static void safeEmit(Consumer<AgentStreamEvent> sink, AgentStreamEvent event) {
        if (sink != null && event != null) {
            sink.accept(event);
        }
    }
}
