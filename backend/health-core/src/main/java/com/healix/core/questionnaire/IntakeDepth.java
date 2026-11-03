package com.healix.core.questionnaire;

import java.util.Locale;
import java.util.Set;

/**
 * C 端首诊填写深度：按题目 {@code level} 过滤。
 *
 * <ul>
 *   <li>MINIMAL — 仅 REQUIRED（极简）
 *   <li>QUICK — REQUIRED + FIRST_VISIT（快速）
 *   <li>FULL — 再加 LATER（完整；永不含 STAFF）
 * </ul>
 */
public enum IntakeDepth {
    MINIMAL("极简", Set.of("REQUIRED")),
    QUICK("快速", Set.of("REQUIRED", "FIRST_VISIT")),
    FULL("完整", Set.of("REQUIRED", "FIRST_VISIT", "LATER"));

    private final String label;
    private final Set<String> levels;

    IntakeDepth(String label, Set<String> levels) {
        this.label = label;
        this.levels = levels;
    }

    public String label() {
        return label;
    }

    public Set<String> levels() {
        return levels;
    }

    public boolean includes(String level) {
        return level != null && levels.contains(level);
    }

    public static IntakeDepth require(String raw) {
        if (raw == null || raw.isBlank()) {
            return MINIMAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知首诊档位: " + raw);
        }
    }

    public static IntakeDepth fromCompletenessJson(String completenessJson) {
        if (completenessJson == null || completenessJson.isBlank()) {
            return MINIMAL;
        }
        try {
            var n = com.healix.common.util.JsonUtils.readTree(completenessJson);
            if (n != null && n.hasNonNull("depth")) {
                return require(n.get("depth").asText());
            }
        } catch (Exception ignored) {
            // fall through
        }
        return MINIMAL;
    }
}
