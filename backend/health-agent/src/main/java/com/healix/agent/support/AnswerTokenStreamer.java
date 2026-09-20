package com.healix.agent.support;

import com.healix.agent.stream.AgentStreamEvent;
import java.util.function.Consumer;

/**
 * 将模型输出中【回答】之后的内容流式推给前端；【思考】段不进气泡。
 * 若模型未按标记分段且正文较长，则整段当作回答流式输出。
 */
public final class AnswerTokenStreamer {

    private static final int PLAIN_ANSWER_THRESHOLD = 80;

    private final Consumer<AgentStreamEvent> sink;
    private final StringBuilder raw = new StringBuilder();
    private boolean answerStarted;
    private boolean inThinking;
    private Runnable onAnswerStart;

    public AnswerTokenStreamer(Consumer<AgentStreamEvent> sink) {
        this.sink = sink;
    }

    public AnswerTokenStreamer onAnswerStart(Runnable hook) {
        this.onAnswerStart = hook;
        return this;
    }

    public void accept(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        raw.append(token);
        if (answerStarted) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(token));
            return;
        }

        String s = raw.toString();
        if (!inThinking) {
            int thinkIdx = indexOfMarker(s, "【思考】");
            if (thinkIdx >= 0) {
                inThinking = true;
            }
        }

        int answerIdx = indexOfMarker(s, "【回答】");
        if (answerIdx >= 0) {
            startAnswer();
            String after = s.substring(answerIdx + "【回答】".length());
            if (after.startsWith("\r\n")) {
                after = after.substring(2);
            } else if (after.startsWith("\n")) {
                after = after.substring(1);
            }
            if (!after.isEmpty()) {
                AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(after));
            }
            return;
        }

        // 无标记：不像 JSON、已有足够正文 → 当作直接回答流式输出
        if (!inThinking && s.length() >= PLAIN_ANSWER_THRESHOLD) {
            String trimmed = s.trim();
            if (!trimmed.startsWith("{") && !trimmed.startsWith("```")) {
                startAnswer();
                AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(s));
            }
        }
    }

    private void startAnswer() {
        if (answerStarted) {
            return;
        }
        answerStarted = true;
        if (onAnswerStart != null) {
            onAnswerStart.run();
        }
    }

    public String raw() {
        return raw.toString();
    }

    public boolean answerStarted() {
        return answerStarted;
    }

    private static int indexOfMarker(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx >= 0) {
            return idx;
        }
        return text.indexOf(marker.replace('【', '[').replace('】', ']'));
    }
}
