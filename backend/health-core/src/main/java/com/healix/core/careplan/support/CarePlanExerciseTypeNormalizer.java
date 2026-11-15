package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * 运动周计划 type 归一：把 LLM 偶发英文别名收成前端有中文映射的规范码，
 * 避免预览直接露出 RELAXATION 等大写英文。
 */
public final class CarePlanExerciseTypeNormalizer {

    /** 别名 / 近义 → 规范码（须与前端 CARE_PLAN_EXERCISE_TYPE_OPTIONS 对齐） */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("RELAXATION", "RELAXATION"),
            Map.entry("RELAX", "RELAXATION"),
            Map.entry("RELAXING", "RELAXATION"),
            Map.entry("COOL_DOWN", "RELAXATION"),
            Map.entry("COOLDOWN", "RELAXATION"),
            Map.entry("MEDITATION", "RELAXATION"),
            Map.entry("MINDFULNESS", "RELAXATION"),
            Map.entry("RELAXATION_BREATHING", "BREATHING"),
            Map.entry("BREATH", "BREATHING"),
            Map.entry("BREATHWORK", "BREATHING"),
            Map.entry("FLEXIBILITY", "STRETCHING"),
            Map.entry("MOBILITY", "STRETCHING"),
            Map.entry("STRETCH", "STRETCHING"),
            Map.entry("WALK", "WALKING"),
            Map.entry("JOG", "JOGGING"),
            Map.entry("RUN", "RUNNING"),
            Map.entry("BIKE", "CYCLING"),
            Map.entry("CYCLE", "CYCLING"),
            Map.entry("AEROBICS", "AEROBIC"),
            Map.entry("RESISTANCE_TRAINING", "RESISTANCE"),
            Map.entry("STRENGTH_TRAINING", "STRENGTH"),
            Map.entry("WEIGHT_TRAINING", "STRENGTH"));

    /** 中文 / 混写 → 规范码 */
    private static final Map<String, String> ZH_ALIASES = Map.ofEntries(
            Map.entry("放松", "RELAXATION"),
            Map.entry("放松练习", "RELAXATION"),
            Map.entry("放松训练", "RELAXATION"),
            Map.entry("冥想", "RELAXATION"),
            Map.entry("拉伸", "STRETCHING"),
            Map.entry("拉伸/柔韧", "STRETCHING"),
            Map.entry("柔韧", "STRETCHING"),
            Map.entry("步行", "WALKING"),
            Map.entry("快走", "BRISK_WALKING"),
            Map.entry("慢跑", "JOGGING"),
            Map.entry("跑步", "RUNNING"),
            Map.entry("呼吸训练", "BREATHING"),
            Map.entry("力量训练", "STRENGTH"),
            Map.entry("抗阻训练", "RESISTANCE"));

    private CarePlanExerciseTypeNormalizer() {}

    /** 原地修正 exercise.weeklyPlan[].items[].type */
    public static void normalize(JsonNode exercise) {
        if (!(exercise instanceof ObjectNode)) {
            return;
        }
        JsonNode plan = exercise.get("weeklyPlan");
        if (!(plan instanceof ArrayNode days)) {
            return;
        }
        for (JsonNode day : days) {
            if (!(day instanceof ObjectNode)) {
                continue;
            }
            JsonNode items = day.get("items");
            if (!(items instanceof ArrayNode arr)) {
                continue;
            }
            for (JsonNode item : arr) {
                if (!(item instanceof ObjectNode obj)) {
                    continue;
                }
                String raw = obj.path("type").asText(null);
                String next = canonicalize(raw);
                if (StringUtils.hasText(next) && !next.equals(raw)) {
                    obj.put("type", next);
                }
            }
        }
    }

    static String canonicalize(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String trimmed = raw.trim();
        String zh = ZH_ALIASES.get(trimmed);
        if (zh != null) {
            return zh;
        }
        String key = trimmed.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String mapped = ALIASES.get(key);
        return mapped != null ? mapped : key;
    }
}
