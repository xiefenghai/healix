package com.healix.core.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 题目显示条件。支持：
 *
 * <ul>
 *   <li>{@code field + eq / in / notIn / intersects}
 *   <li>{@code all} / {@code any} 组合
 * </ul>
 */
public record ShowIf(
        String field,
        String eq,
        List<String> in,
        List<String> notIn,
        List<String> intersects,
        List<ShowIf> all,
        List<ShowIf> any) {

    static ShowIf parse(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.has("_unparsed")) {
            throw new IllegalStateException("显示条件未解析: " + n.get("_unparsed").asText());
        }
        if (n.has("all")) {
            List<ShowIf> kids = new ArrayList<>();
            n.get("all").forEach(c -> kids.add(parse(c)));
            return new ShowIf(null, null, null, null, null, List.copyOf(kids), null);
        }
        if (n.has("any")) {
            List<ShowIf> kids = new ArrayList<>();
            n.get("any").forEach(c -> kids.add(parse(c)));
            return new ShowIf(null, null, null, null, null, null, List.copyOf(kids));
        }
        String field = text(n, "field");
        if (field == null) {
            throw new IllegalStateException("显示条件缺少 field: " + n);
        }
        return new ShowIf(
                field,
                text(n, "eq"),
                strList(n, "in"),
                strList(n, "notIn"),
                strList(n, "intersects"),
                null,
                null);
    }

    public boolean matches(java.util.function.Function<String, JsonNode> values) {
        if (all != null) {
            return all.stream().allMatch(s -> s.matches(values));
        }
        if (any != null) {
            return any.stream().anyMatch(s -> s.matches(values));
        }
        JsonNode v = values.apply(field);
        List<String> actual = flatten(v);
        if (eq != null) {
            return actual.size() == 1 && eq.equalsIgnoreCase(actual.get(0));
        }
        if (in != null) {
            return actual.stream().anyMatch(a -> containsIgnoreCase(in, a));
        }
        if (notIn != null) {
            if (actual.isEmpty()) {
                return true;
            }
            return actual.stream().noneMatch(a -> containsIgnoreCase(notIn, a));
        }
        if (intersects != null) {
            return actual.stream().anyMatch(a -> containsIgnoreCase(intersects, a));
        }
        return true;
    }

    private static boolean containsIgnoreCase(List<String> list, String v) {
        for (String s : list) {
            if (s.equalsIgnoreCase(v)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> flatten(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull() || v.isMissingNode()) {
            return out;
        }
        if (v.isArray()) {
            v.forEach(n -> {
                String t = n.asText(null);
                if (t != null && !t.isBlank()) {
                    out.add(t.trim());
                }
            });
        } else if (v.isTextual() || v.isNumber() || v.isBoolean()) {
            String t = v.asText();
            if (t != null && !t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static List<String> strList(JsonNode n, String key) {
        JsonNode v = n.get(key);
        if (v == null || !v.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        v.forEach(x -> out.add(x.asText()));
        return List.copyOf(out);
    }
}
