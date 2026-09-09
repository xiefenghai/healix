package com.healix.web.support;

import com.healix.agent.stream.AgentStreamEvent;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class AgentSseSupport {

    private AgentSseSupport() {}

    public static SseEmitter createEmitter(long timeoutMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    public static void prepareSseResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CONNECTION, "keep-alive");
    }

    public static void openStream(SseEmitter emitter, HttpServletResponse response) {
        try {
            emitter.send(SseEmitter.event().comment("open"));
            response.flushBuffer();
        } catch (IOException ignored) {
            // best effort
        }
    }

    public static Consumer<AgentStreamEvent> sseSink(SseEmitter emitter, HttpServletResponse response) {
        return event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(event.toJsonLine(), MediaType.TEXT_PLAIN));
                response.flushBuffer();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        };
    }
}
