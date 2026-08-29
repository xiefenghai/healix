package com.healix.core.revision.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class FieldDiffUtils {

    private FieldDiffUtils() {}

    public record FieldChange(String fieldPath, JsonNode oldValue, JsonNode newValue) {}

    public static List<FieldChange> diffJson(String oldJson, String newJson) {
        JsonNode oldRoot = JsonUtils.readTree(oldJson == null ? "{}" : oldJson);
        JsonNode newRoot = JsonUtils.readTree(newJson == null ? "{}" : newJson);
        List<FieldChange> changes = new ArrayList<>();
        collectDiff("", oldRoot, newRoot, changes);
        return changes;
    }

    private static void collectDiff(String prefix, JsonNode oldNode, JsonNode newNode, List<FieldChange> out) {
        if (oldNode == null || oldNode.isNull()) {
            oldNode = JsonUtils.emptyObject();
        }
        if (newNode == null || newNode.isNull()) {
            newNode = JsonUtils.emptyObject();
        }
        if (oldNode.isObject() && newNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = newNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String key = e.getKey();
                if ("schemaVersion".equals(key)) {
                    continue;
                }
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                JsonNode oldChild = oldNode.get(key);
                JsonNode newChild = e.getValue();
                if (newChild != null && newChild.isObject() && !newChild.isEmpty()) {
                    if (oldChild != null && oldChild.isObject()) {
                        collectDiff(path, oldChild, newChild, out);
                    } else {
                        flattenLeaf(path, oldChild, newChild, out);
                    }
                } else {
                    if (!jsonEquals(oldChild, newChild)) {
                        out.add(new FieldChange(path, oldChild, newChild));
                    }
                }
            }
            Iterator<Map.Entry<String, JsonNode>> oldFields = oldNode.fields();
            while (oldFields.hasNext()) {
                Map.Entry<String, JsonNode> e = oldFields.next();
                String key = e.getKey();
                if ("schemaVersion".equals(key) || newNode.has(key)) {
                    continue;
                }
                String path = prefix.isEmpty() ? key : prefix + "." + key;
                JsonNode oldChild = e.getValue();
                if (oldChild != null && oldChild.isObject()) {
                    collectDiff(path, oldChild, JsonUtils.emptyObject(), out);
                } else {
                    out.add(new FieldChange(path, oldChild, null));
                }
            }
            return;
        }
        if (!jsonEquals(oldNode, newNode)) {
            out.add(new FieldChange(prefix, oldNode, newNode));
        }
    }

    private static void flattenLeaf(String path, JsonNode oldNode, JsonNode newNode, List<FieldChange> out) {
        if (newNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = newNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                String subPath = path + "." + e.getKey();
                JsonNode oldChild = oldNode != null && oldNode.isObject() ? oldNode.get(e.getKey()) : null;
                if (!jsonEquals(oldChild, e.getValue())) {
                    out.add(new FieldChange(subPath, oldChild, e.getValue()));
                }
            }
        }
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || a.isNull()) {
            return b == null || b.isNull();
        }
        return Objects.equals(a, b);
    }
}
