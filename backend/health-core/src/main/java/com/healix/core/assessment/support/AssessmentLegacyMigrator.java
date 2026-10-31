package com.healix.core.assessment.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.AssessmentFields;
import com.healix.core.assessment.catalog.InputFieldMapping;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 评估问卷存量迁移：fields_json 枚举名→编码、submission 答案键转换、fact 值换算。
 *
 * <p>纯函数，便于单测；实际落库由 {@code SchemaEnsureRunner} 调用。
 */
public final class AssessmentLegacyMigrator {

    private AssessmentLegacyMigrator() {}

    /** request.fields_json：旧枚举名数组 → 字段编码数组。 */
    public static String convertFieldsJson(String fieldsJson) {
        if (fieldsJson == null || fieldsJson.isBlank()) {
            return "[]";
        }
        JsonNode node = JsonUtils.readTree(fieldsJson);
        List<String> raw = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(n -> raw.add(n.asText()));
        }
        return JsonUtils.toJson(InputFieldMapping.convertFieldList(raw));
    }

    /** submission.answers_json：键从枚举名改为编码，并做值换算。 */
    public static String convertAnswersJson(String answersJson) {
        if (answersJson == null || answersJson.isBlank()) {
            return "{}";
        }
        JsonNode root = JsonUtils.readTree(answersJson);
        if (!root.isObject()) {
            return "{}";
        }
        ObjectNode out = JsonUtils.emptyObject();
        Iterator<Map.Entry<String, JsonNode>> it = root.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            Map<String, JsonNode> converted = convertAnswerEntry(e.getKey(), e.getValue());
            converted.forEach(out::set);
        }
        return JsonUtils.toJson(out);
    }

    /**
     * 单条 fact → 元数据写入条目（可能多键）。
     *
     * @return metadataCode → valueJson；空表示跳过
     */
    public static Map<String, JsonNode> convertFact(String legacyField, JsonNode valueNode) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (legacyField == null || valueNode == null || valueNode.isNull()) {
            return out;
        }
        JsonNode value = valueNode.has("value") ? valueNode.get("value") : valueNode;
        String legacy = legacyField.trim().toUpperCase(Locale.ROOT);
        return switch (legacy) {
            case "HIGH_SALT" -> {
                Boolean yn = yesNo(value);
                if (yn == null) {
                    yield out;
                }
                ArrayNode taste = JsonUtils.mapper().createArrayNode();
                if (yn) {
                    taste.add("SALTY");
                }
                out.put(AssessmentFields.HIGH_SALT, taste);
                yield out;
            }
            case "CHRONIC_STRESS" -> {
                Boolean yn = yesNo(value);
                if (yn == null) {
                    yield out;
                }
                out.put(
                        AssessmentFields.CHRONIC_STRESS,
                        JsonUtils.mapper().valueToTree(yn ? "HIGH" : "MEDIUM"));
                yield out;
            }
            case "PREDIABETES", "PCOS", "OSA" -> {
                // 并入 presentIllness 由调用方合并；这里返回标记
                Boolean yn = yesNo(value);
                if (!Boolean.TRUE.equals(yn)) {
                    yield out;
                }
                String code =
                        switch (legacy) {
                            case "PREDIABETES" -> AssessmentFields.ILLNESS_PREDIABETES;
                            case "PCOS" -> AssessmentFields.ILLNESS_PCOS;
                            default -> AssessmentFields.ILLNESS_OSA;
                        };
                ArrayNode arr = JsonUtils.mapper().createArrayNode();
                arr.add(code);
                out.put(AssessmentFields.PRESENT_ILLNESS, arr);
                yield out;
            }
            case "ANTIHYPERTENSIVE", "STATIN", "PSYCH_DRUGS" -> {
                Boolean yn = yesNo(value);
                if (!Boolean.TRUE.equals(yn)) {
                    yield out;
                }
                String flag =
                        switch (legacy) {
                            case "ANTIHYPERTENSIVE" -> AssessmentFields.MED_ANTIHYPERTENSIVE;
                            case "STATIN" -> AssessmentFields.MED_STATIN;
                            default -> AssessmentFields.MED_PSYCH;
                        };
                ArrayNode arr = JsonUtils.mapper().createArrayNode();
                arr.add(flag);
                out.put(AssessmentFields.MEDICATION_FLAGS, arr);
                yield out;
            }
            case "BODY_FAT" -> {
                out.put(AssessmentFields.BODY_FAT, value);
                yield out;
            }
            case "DRINKING" -> {
                String v = value.asText("").toUpperCase(Locale.ROOT);
                if ("NEVER".equals(v) || "NONE".equals(v) || "FORMER".equals(v)) {
                    out.put(AssessmentFields.DRINKING_STATUS, JsonUtils.mapper().valueToTree("NEVER"));
                } else {
                    out.put(AssessmentFields.DRINKING_STATUS, JsonUtils.mapper().valueToTree("CURRENT"));
                    out.put(AssessmentFields.DRINKING_FREQUENCY, value);
                }
                yield out;
            }
            default -> {
                List<String> codes = InputFieldMapping.resolve(legacy);
                if (codes.isEmpty()) {
                    yield out;
                }
                out.put(codes.get(0), value);
                yield out;
            }
        };
    }

    private static Map<String, JsonNode> convertAnswerEntry(String key, JsonNode value) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        String legacy = key == null ? "" : key.trim();
        if (InputFieldMapping.legacyNames().contains(legacy.toUpperCase(Locale.ROOT))) {
            legacy = legacy.toUpperCase(Locale.ROOT);
            if ("DRINKING".equals(legacy)) {
                String v = value.asText("").toUpperCase(Locale.ROOT);
                if ("NEVER".equals(v) || "NONE".equals(v)) {
                    out.put(AssessmentFields.DRINKING_STATUS, JsonUtils.mapper().valueToTree("NEVER"));
                } else {
                    out.put(AssessmentFields.DRINKING_STATUS, JsonUtils.mapper().valueToTree("CURRENT"));
                    out.put(AssessmentFields.DRINKING_FREQUENCY, value);
                }
                return out;
            }
            Map<String, JsonNode> factStyle = convertFact(legacy, wrapValue(value));
            if (!factStyle.isEmpty()) {
                return factStyle;
            }
            List<String> codes = InputFieldMapping.codesOf(legacy);
            out.put(codes.get(0), value);
            return out;
        }
        out.put(key, value);
        return out;
    }

    private static JsonNode wrapValue(JsonNode value) {
        ObjectNode n = JsonUtils.emptyObject();
        n.set("value", value);
        return n;
    }

    private static Boolean yesNo(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText("").trim().toUpperCase(Locale.ROOT);
        if ("YES".equals(s) || "TRUE".equals(s)) {
            return true;
        }
        if ("NO".equals(s) || "FALSE".equals(s)) {
            return false;
        }
        return null;
    }

    /** 合并 presentIllness / medicationFlags 增量（用于多条 fact）。 */
    public static JsonNode mergeMulti(JsonNode current, JsonNode add) {
        Set<String> codes = new LinkedHashSet<>();
        if (current != null && current.isArray()) {
            current.forEach(n -> {
                if (n != null && !n.asText("").isBlank()) {
                    codes.add(n.asText().trim().toUpperCase(Locale.ROOT));
                }
            });
        }
        if (add != null && add.isArray()) {
            add.forEach(n -> {
                if (n != null && !n.asText("").isBlank()) {
                    codes.add(n.asText().trim().toUpperCase(Locale.ROOT));
                }
            });
        }
        codes.remove("NONE");
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        codes.forEach(out::add);
        return out;
    }
}
