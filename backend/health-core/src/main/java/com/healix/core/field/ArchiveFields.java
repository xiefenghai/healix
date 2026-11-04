package com.healix.core.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;

/**
 * 档案 JSON 与字段值互转。
 *
 * <ul>
 *   <li>扁平化：目录里登记的编码是叶子；没登记但下面还有登记字段的对象继续往下拆；其余整体作为一个未登记字段原样保留
 *   <li>重建：按编码路径拼回 JSON，作为现有读取方的兼容视图
 * </ul>
 */
@Slf4j
public final class ArchiveFields {

    /** 档案 JSON 顶层的格式版本键，不当作字段。 */
    static final String SCHEMA_KEY = "schemaVersion";

    private ArchiveFields() {}

    /** @param prefix {@code basic.} 或 {@code disease.hypertension.} */
    public static Map<String, JsonNode> flatten(String prefix, JsonNode content, FieldCatalog catalog) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (content == null || !content.isObject()) {
            return out;
        }
        ObjectNode normalized = normalizeLegacy(prefix, (ObjectNode) content.deepCopy());
        walk(prefix, normalized, catalog, out, true);
        return out;
    }

    private static void walk(String path, JsonNode node, FieldCatalog catalog, Map<String, JsonNode> out, boolean root) {
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (root && SCHEMA_KEY.equals(e.getKey())) {
                continue;
            }
            String code = path + e.getKey();
            JsonNode v = e.getValue();
            if (catalog.contains(code)) {
                if (!isEmpty(v)) {
                    out.put(code, v);
                }
            } else if (v.isObject() && catalog.hasChildren(code)) {
                walk(code + ".", v, catalog, out, false);
            } else if (!isEmpty(v)) {
                out.put(code, v);
            }
        }
    }

    /** 旧版档案吸烟 / 饮酒存成字符串，统一成 {@code {status}}。 */
    private static ObjectNode normalizeLegacy(String prefix, ObjectNode root) {
        if (!FieldStore.BASIC.prefix().equals(prefix)) {
            return root;
        }
        if (root.get("lifestyle") instanceof ObjectNode lifestyle) {
            for (String key : new String[] {"smoking", "drinking"}) {
                JsonNode v = lifestyle.get(key);
                if (v != null && v.isTextual()) {
                    ObjectNode wrapped = JsonUtils.emptyObject();
                    if (!v.asText().isBlank()) {
                        wrapped.put("status", v.asText());
                    }
                    lifestyle.set(key, wrapped);
                }
            }
        }
        return root;
    }

    /** 按编码路径拼回 JSON；同一路径既有值又有子字段时子字段优先（记日志，属数据异常）。 */
    public static ObjectNode assemble(String prefix, Map<String, JsonNode> values) {
        ObjectNode root = JsonUtils.emptyObject();
        // 先短后长：父路径的值先放，子路径再覆盖成对象
        Map<String, JsonNode> sorted = new TreeMap<>((a, b) -> a.length() != b.length() ? a.length() - b.length() : a.compareTo(b));
        sorted.putAll(values);
        for (Map.Entry<String, JsonNode> e : sorted.entrySet()) {
            if (!e.getKey().startsWith(prefix) || isEmpty(e.getValue())) {
                continue;
            }
            String[] parts = e.getKey().substring(prefix.length()).split("\\.");
            ObjectNode cur = root;
            for (int i = 0; i < parts.length - 1; i++) {
                JsonNode child = cur.get(parts[i]);
                ObjectNode next;
                if (child instanceof ObjectNode objectNode) {
                    next = objectNode;
                } else {
                    if (child != null) {
                        log.warn("field path conflict at {}{}: value replaced by sub-fields", prefix, parts[i]);
                    }
                    next = cur.putObject(parts[i]);
                }
                cur = next;
            }
            String leaf = parts[parts.length - 1];
            if (cur.get(leaf) instanceof ObjectNode && !e.getValue().isObject()) {
                log.warn("field path conflict at {}: sub-fields kept", e.getKey());
                continue;
            }
            cur.set(leaf, e.getValue());
        }
        return root;
    }

    public static boolean isEmpty(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        if (node.isArray()) {
            return node.isEmpty();
        }
        if (node.isObject()) {
            Iterator<JsonNode> it = node.elements();
            while (it.hasNext()) {
                if (!isEmpty(it.next())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
