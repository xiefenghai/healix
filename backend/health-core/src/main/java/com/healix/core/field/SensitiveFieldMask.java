package com.healix.core.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.identity.enums.StaffRoleEnum;
import java.util.Map;
import java.util.Set;

/**
 * 敏感字段按角色脱敏：运营 / 租户管理员等不可见明细，仅保留「已采集」。
 *
 * <p>可见：健管师、营养师、医生；其余角色对 {@code sensitive=true} 字段脱敏。
 */
public final class SensitiveFieldMask {

    public static final String MASKED = "已采集";

    private SensitiveFieldMask() {}

    public static boolean canViewSensitive(StaffRoleEnum role) {
        if (role == null) {
            return false;
        }
        return role == StaffRoleEnum.CARE_MANAGER
                || role == StaffRoleEnum.NUTRITIONIST
                || role == StaffRoleEnum.DOCTOR;
    }

    public static boolean canViewSensitive(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return false;
        }
        try {
            return canViewSensitive(StaffRoleEnum.valueOf(roleCode.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /** 对 content_json 兼容视图中敏感路径脱敏（原地）。 */
    public static JsonNode maskBasicContent(JsonNode content, FieldCatalog catalog, boolean canView) {
        if (canView || content == null || !content.isObject() || catalog == null) {
            return content;
        }
        ObjectNode root = (ObjectNode) content.deepCopy();
        for (FieldDef def : catalog.all()) {
            if (!def.sensitive() || !def.code().startsWith("basic.")) {
                continue;
            }
            maskPath(root, def.code().substring("basic.".length()));
        }
        return root;
    }

    /** 扁平 answers / 字段 map：敏感键替换为占位文案。 */
    public static Map<String, JsonNode> maskFlat(Map<String, JsonNode> values, FieldCatalog catalog, boolean canView) {
        if (canView || values == null || values.isEmpty() || catalog == null) {
            return values;
        }
        for (Map.Entry<String, JsonNode> e : values.entrySet()) {
            catalog.find(e.getKey()).ifPresent(def -> {
                if (def.sensitive() && e.getValue() != null && !ArchiveFields.isEmpty(e.getValue())) {
                    e.setValue(JsonUtils.mapper().getNodeFactory().textNode(MASKED));
                }
            });
        }
        return values;
    }

    private static void maskPath(ObjectNode root, String relativePath) {
        String[] parts = relativePath.split("\\.");
        ObjectNode cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = cur.get(parts[i]);
            if (!(child instanceof ObjectNode)) {
                return;
            }
            cur = (ObjectNode) child;
        }
        String leaf = parts[parts.length - 1];
        JsonNode v = cur.get(leaf);
        if (v != null && !ArchiveFields.isEmpty(v)) {
            // 数组/对象不能改成「已采集」字符串，否则前端勾选组会当成空；改用空容器 + 根标记
            if (v.isArray()) {
                cur.set(leaf, JsonUtils.mapper().createArrayNode());
                root.put("_sensitiveMasked", true);
            } else if (v.isObject()) {
                cur.set(leaf, JsonUtils.emptyObject());
                root.put("_sensitiveMasked", true);
            } else {
                cur.put(leaf, MASKED);
                root.put("_sensitiveMasked", true);
            }
        }
    }

    /** 高亮数组：对敏感 field 去掉答案细节，只留 field + severity。 */
    public static JsonNode maskHighlights(JsonNode highlights, FieldCatalog catalog, boolean canView) {
        if (canView || highlights == null || !highlights.isArray() || catalog == null) {
            return highlights;
        }
        var arr = JsonUtils.mapper().createArrayNode();
        for (JsonNode h : highlights) {
            String field = h.path("field").asText("");
            boolean sens = catalog.find(field).map(FieldDef::sensitive).orElse(false);
            if (!sens) {
                arr.add(h);
                continue;
            }
            ObjectNode copy = JsonUtils.emptyObject();
            copy.put("field", field);
            copy.put("severity", h.path("severity").asText(""));
            copy.put("masked", true);
            arr.add(copy);
        }
        return arr;
    }

    public static boolean anyRoleCanView(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return false;
        }
        return roleCodes.stream().anyMatch(SensitiveFieldMask::canViewSensitive);
    }
}
