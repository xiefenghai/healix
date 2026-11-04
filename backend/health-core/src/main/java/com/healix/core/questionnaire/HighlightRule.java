package com.healix.core.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 题目高亮规则：命中条件 → CAUTION / REFER / BLOCK。 */
public record HighlightRule(
        JsonNode when, String severity, String followUp, String planEffect) {

    static final Set<String> SEVERITIES = Set.of("CAUTION", "REFER", "BLOCK");

    static HighlightRule parse(JsonNode n) {
        String severity = n.path("severity").asText("").trim().toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new IllegalStateException("高亮级别不合法: " + severity);
        }
        JsonNode when = n.get("when");
        if (when == null || when.isNull()) {
            throw new IllegalStateException("高亮缺少 when");
        }
        String followUp = text(n, "followUp");
        String planEffect = text(n, "planEffect");
        return new HighlightRule(when, severity, followUp, planEffect);
    }

    static List<HighlightRule> parseList(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return List.of();
        }
        List<HighlightRule> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(parse(x)));
        } else {
            out.add(parse(n));
        }
        return List.copyOf(out);
    }

    /** 用当前字段值判定本规则是否命中（when 针对该题自己的答案）。 */
    public boolean matches(JsonNode answer) {
        if (when.has("always") && when.path("always").asBoolean(false)) {
            return true;
        }
        List<String> actual = flatten(answer);
        if (when.has("eq")) {
            String eq = when.path("eq").asText();
            return actual.size() == 1 && eq.equalsIgnoreCase(actual.get(0));
        }
        if (when.has("in")) {
            List<String> want = new ArrayList<>();
            when.get("in").forEach(x -> want.add(x.asText()));
            return actual.stream().anyMatch(a -> want.stream().anyMatch(w -> w.equalsIgnoreCase(a)));
        }
        if (when.has("notIn")) {
            List<String> ban = new ArrayList<>();
            when.get("notIn").forEach(x -> ban.add(x.asText()));
            if (actual.isEmpty()) {
                return false;
            }
            return actual.stream().noneMatch(a -> ban.stream().anyMatch(w -> w.equalsIgnoreCase(a)));
        }
        if (when.has("intersects")) {
            List<String> want = new ArrayList<>();
            when.get("intersects").forEach(x -> want.add(x.asText()));
            return actual.stream().anyMatch(a -> want.stream().anyMatch(w -> w.equalsIgnoreCase(a)));
        }
        if (when.has("op") && when.has("value")) {
            if (actual.isEmpty()) {
                return false;
            }
            try {
                double num = Double.parseDouble(actual.get(0));
                double thr = when.path("value").asDouble();
                return compare(num, when.path("op").asText(), thr);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        // text / computed 等留给上层结合上下文再判；此处不命中
        return when.has("text") || when.has("computed") ? false : false;
    }

    private static boolean compare(double num, String op, double thr) {
        return switch (op) {
            case "<" -> num < thr;
            case "<=" , "≤" -> num <= thr;
            case ">" -> num > thr;
            case ">=" , "≥" -> num >= thr;
            default -> false;
        };
    }

    private static List<String> flatten(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull() || v.isMissingNode()) {
            return out;
        }
        if (v.isArray()) {
            v.forEach(n -> {
                if (!n.asText("").isBlank()) {
                    out.add(n.asText().trim());
                }
            });
        } else if (!v.asText("").isBlank()) {
            out.add(v.asText().trim());
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
