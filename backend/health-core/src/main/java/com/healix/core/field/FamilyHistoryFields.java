package com.healix.core.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 虚拟字段 {@code basic.familyHistory.firstDegree} ↔ {@code familyHistoryItems} /
 * {@code familyHistoryStatus}。
 */
public final class FamilyHistoryFields {

    public static final String VIRTUAL = "basic.familyHistory.firstDegree";
    public static final String ITEMS = "basic.familyHistoryItems";
    public static final String STATUS = "basic.familyHistoryStatus";

    private FamilyHistoryFields() {}

    /** 从明细推导一级亲属病种多选；无明细时不返回。 */
    public static JsonNode read(Map<String, JsonNode> values) {
        JsonNode items = values.get(ITEMS);
        if (items == null || !items.isArray() || items.isEmpty()) {
            String status = text(values.get(STATUS));
            if ("none".equalsIgnoreCase(status)) {
                ArrayNode none = JsonUtils.mapper().createArrayNode();
                none.add("NONE");
                return none;
            }
            return null;
        }
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (JsonNode item : items) {
            if (!"1".equals(item.path("kinshipLevel").asText("1"))) {
                continue;
            }
            for (JsonNode d : item.path("diseases")) {
                String code = d.asText("").trim().toUpperCase(Locale.ROOT);
                if (!code.isBlank()) {
                    codes.add(code);
                }
            }
        }
        if (codes.isEmpty()) {
            return null;
        }
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        codes.forEach(out::add);
        return out;
    }

    /**
     * 把虚拟字段写回展开为明细 / 状态的变更（仅改一级亲属条目）。
     *
     * @return 需要写入的真实字段；{@code value == null} 表示清空
     */
    public static Map<String, JsonNode> expandWrite(JsonNode virtualValue, Map<String, JsonNode> current) {
        List<String> picked = toCodes(virtualValue);
        Set<String> wanted = new LinkedHashSet<>(picked);
        wanted.remove("NONE");
        wanted.remove("UNKNOWN");

        ArrayNode items =
                current.get(ITEMS) instanceof ArrayNode a
                        ? (ArrayNode) a.deepCopy()
                        : JsonUtils.mapper().createArrayNode();
        ArrayNode kept = JsonUtils.mapper().createArrayNode();
        Set<String> present = new HashSet<>();
        ObjectNode unspecified = null;
        for (JsonNode item : items) {
            if (!(item instanceof ObjectNode o) || !"1".equals(o.path("kinshipLevel").asText("1"))) {
                kept.add(item);
                continue;
            }
            ArrayNode diseases = JsonUtils.mapper().createArrayNode();
            for (JsonNode d : o.path("diseases")) {
                String code = d.asText("").trim().toUpperCase(Locale.ROOT);
                if (wanted.contains(code)) {
                    diseases.add(code);
                    present.add(code);
                }
            }
            if (diseases.isEmpty()) {
                continue;
            }
            o.set("diseases", diseases);
            if ("UNSPECIFIED".equals(o.path("member").asText())) {
                unspecified = o;
            }
            kept.add(o);
        }
        List<String> toAdd = wanted.stream().filter(c -> !present.contains(c)).toList();
        if (!toAdd.isEmpty()) {
            if (unspecified == null) {
                unspecified = JsonUtils.mapper().createObjectNode();
                unspecified.put("kinshipLevel", "1");
                unspecified.put("member", "UNSPECIFIED");
                unspecified.putArray("diseases");
                kept.add(unspecified);
            }
            ArrayNode diseases = (ArrayNode) unspecified.get("diseases");
            toAdd.forEach(diseases::add);
        }

        java.util.LinkedHashMap<String, JsonNode> out = new java.util.LinkedHashMap<>();
        if (kept.isEmpty() && (picked.contains("NONE") || ArchiveFields.isEmpty(virtualValue))) {
            out.put(ITEMS, null);
            out.put(STATUS, JsonUtils.mapper().getNodeFactory().textNode("none"));
        } else {
            out.put(ITEMS, kept);
            out.put(STATUS, JsonUtils.mapper().getNodeFactory().textNode(kept.isEmpty() ? "none" : "has"));
        }
        return out;
    }

    private static List<String> toCodes(JsonNode v) {
        List<String> out = new ArrayList<>();
        if (v == null || v.isNull()) {
            return out;
        }
        if (v.isArray()) {
            for (JsonNode n : v) {
                String c = n.asText("").trim().toUpperCase(Locale.ROOT);
                if (!c.isBlank()) {
                    out.add(c);
                }
            }
        } else if (v.isTextual()) {
            String c = v.asText().trim().toUpperCase(Locale.ROOT);
            if (!c.isBlank()) {
                out.add(c);
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() ? null : n.asText(null);
    }
}
