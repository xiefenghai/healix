package com.healix.agent.support;

import com.healix.agent.stream.AgentStreamEvent;
import java.util.function.Consumer;

/**
 * 将模型输出中【回答】之后的内容流式推给前端；【思考】段不进气泡。
 * 无标记时尽早当作回答流式输出；流结束仍未开始回答时由 {@link #finish(String)} 补推。
 */
public final class AnswerTokenStreamer {

    private static final int PLAIN_ANSWER_THRESHOLD = 16;

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

    /**
     * 流结束仍未进入回答段时，把回答正文按块补推，避免气泡「突然整段出现」。
     */
    public void finish(String answerFallback) {
        if (answerStarted || sink == null) {
            return;
        }
        String answer = answerFallback;
        if (answer == null || answer.isBlank()) {
            answer = stripToAnswer(raw.toString());
        }
        if (answer == null || answer.isBlank()) {
            return;
        }
        startAnswer();
        int i = 0;
        while (i < answer.length()) {
            int end = Math.min(i + 28, answer.length());
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(answer.substring(i, end)));
            i = end;
        }
    }

    private static String stripToAnswer(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        int answerIdx = indexOfMarker(rawText, "【回答】");
        if (answerIdx >= 0) {
            return rawText.substring(answerIdx + "【回答】".length()).trim();
        }
        int thinkIdx = indexOfMarker(rawText, "【思考】");
        if (thinkIdx >= 0) {
            // 只有思考没有回答：不把思考当气泡正文
            return "";
        }
        String trimmed = rawText.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("```")) {
            return "";
        }
        return trimmed;
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
