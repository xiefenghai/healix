package com.healix.agent.report;

import com.healix.agent.stream.AgentStreamEvent;
import java.util.function.Consumer;

/**
 * 从报告点评 JSON 流中提取可读字段，按「健管师寄语 → 下阶段关注 → 阶段建议」增量推送。
 */
final class ReportReadableStreamer {

    private final Consumer<AgentStreamEvent> sink;
    private final StringBuilder raw = new StringBuilder();

    private final Section comment = new Section("健管师寄语\n");
    private final Section focus = new Section("下阶段关注\n");
    private final Section advice = new Section("阶段建议\n");

    ReportReadableStreamer(Consumer<AgentStreamEvent> sink) {
        this.sink = sink;
    }

    void onToken(String token) {
        if (token == null || token.isEmpty() || sink == null) {
            return;
        }
        raw.append(token);
        String s = raw.toString();
        pumpPartial(comment, partialJsonString(s, "staffComment"));
        pumpPartial(focus, partialJsonString(s, "nextFocus"));
        pumpPartial(advice, partialJsonString(s, "quarterAdvice"));
    }

    private void pumpPartial(Section section, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        pump(section, value);
    }

    private void pump(Section section, String body) {
        if (body == null || body.isEmpty() || sink == null) {
            return;
        }
        if (!body.startsWith(section.body)) {
            return;
        }
        if (body.length() == section.body.length()) {
            return;
        }
        if (!section.headerSent) {
            String prefix = anyHeaderSent() ? "\n\n" : "";
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(prefix + section.header));
            section.headerSent = true;
            sleepQuiet(10);
        }
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token(body.substring(section.body.length())));
        section.body = body;
        sleepQuiet(8);
    }

    private boolean anyHeaderSent() {
        return comment.headerSent || focus.headerSent || advice.headerSent;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 抽取 JSON 字符串字段的已输出部分（含未闭合）。 */
    static String partialJsonString(String s, String field) {
        String needle = "\"" + field + "\"";
        int key = s.indexOf(needle);
        if (key < 0) {
            return null;
        }
        int colon = s.indexOf(':', key + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i >= s.length() || s.charAt(i) != '"') {
            return null;
        }
        StringBuilder out = new StringBuilder();
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(n);
                }
                i += 2;
                continue;
            }
            if (c == '"') {
                break;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static final class Section {
        final String header;
        boolean headerSent;
        String body = "";

        Section(String header) {
            this.header = header;
        }
    }
}
