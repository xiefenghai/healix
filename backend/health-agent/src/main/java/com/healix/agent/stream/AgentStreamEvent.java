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
        data.put("status", status == null ? "running" : status);
        data.put("detail", detail == null ? "" : detail);
        return of("tool", data);
    }

    public static AgentStreamEvent skill(String name, String detail) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("detail", detail == null ? "" : detail);
        return of("skill", data);
    }

    /**
     * 思考过程事件。
     *
     * @param status start | delta | done
     * @param text 展示文案或增量内容
     * @param elapsedMs 仅 done 时有意义
     */
    public static AgentStreamEvent thinking(String status, String text, Long elapsedMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", status == null ? "delta" : status);
        if (text != null) {
            data.put("text", text);
        }
        if (elapsedMs != null) {
            data.put("elapsedMs", elapsedMs);
        }
        return of("thinking", data);
    }

    public static AgentStreamEvent thinkingStart(String text) {
        return thinking("start", text, null);
    }

    public static AgentStreamEvent thinkingDelta(String text) {
        return thinking("delta", text, null);
    }

    public static AgentStreamEvent thinkingDone(String text, long elapsedMs) {
        return thinking("done", text, elapsedMs);
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
        return done(null);
    }

    public static AgentStreamEvent done(Long elapsedMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (elapsedMs != null) {
            data.put("elapsedMs", elapsedMs);
        }
        return of("done", data);
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
