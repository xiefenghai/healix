package com.healix.web.support;

import com.healix.agent.stream.AgentStreamEvent;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class AgentSseSupport {

    private AgentSseSupport() {}

    public static SseEmitter createEmitter(long timeoutMs) {
        return new SseEmitter(timeoutMs);
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
        } catch (Exception ignored) {
            // best effort
        }
    }

    /**
     * SSE 下发；客户端切会话/关页后 emitter 会 complete，后续 token 静默丢弃，
     * 不向上抛，避免打断 LLM 流导致误报「LLM failed」并影响成对落库。
     */
    public static Consumer<AgentStreamEvent> sseSink(SseEmitter emitter, HttpServletResponse response) {
        AtomicBoolean closed = new AtomicBoolean(false);
        Runnable markClosed = () -> closed.set(true);
        emitter.onCompletion(markClosed);
        emitter.onTimeout(() -> {
            markClosed.run();
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // already completed
            }
        });
        emitter.onError(e -> markClosed.run());
        return event -> {
            if (closed.get() || event == null) {
                return;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type())
                        .data(event.toJsonLine(), MediaType.TEXT_PLAIN));
                response.flushBuffer();
            } catch (Exception e) {
                // IOException / IllegalStateException("already completed")：客户端已走
                closed.set(true);
            }
        };
    }

    /** 安全结束 SSE，避免重复 complete 再抛 IllegalStateException。 */
    public static void completeQuietly(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // already completed / timeout
        }
    }
}
