package com.healix.core.questionnaire;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;

/** 模块内一道题。 */
public record ModuleItem(
        String field,
        String label,
        String level,
        ShowIf showIf,
        List<HighlightRule> highlights,
        boolean sensitive) {

    static final Set<String> LEVELS = Set.of("REQUIRED", "FIRST_VISIT", "LATER", "STAFF");

    static ModuleItem parse(JsonNode n) {
        String field = text(n, "field");
        String label = text(n, "label");
        String level = text(n, "level");
        if (field == null || label == null) {
            throw new IllegalStateException("题目缺少 field/label: " + n);
        }
        if (level == null || !LEVELS.contains(level)) {
            throw new IllegalStateException("题目级别不合法: " + field + " / " + level);
        }
        ShowIf showIf = ShowIf.parse(n.get("showIf"));
        List<HighlightRule> highlights = HighlightRule.parseList(
                n.has("highlights") ? n.get("highlights") : n.get("highlight"));
        return new ModuleItem(
                field, label, level, showIf, highlights, n.path("sensitive").asBoolean(false));
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }
}
