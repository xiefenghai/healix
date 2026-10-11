package com.healix.core.archive.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.dict.dto.DictItemDto;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 档案完整度计分：以 FIELD / DISEASE_FIELD 字典为分母，content_json 有值计分子。
 *
 * <ul>
 *   <li>COMPOSITE：按子字段展开计数（任一子字段有值即该子字段计 1）
 *   <li>非 COMPOSITE：字段本身计 1
 *   <li>家族史：已确认 {@code familyHistoryStatus=none|has} 即视为已填（即使摘要为空）
 * </ul>
 */
public final class ArchiveCompletenessScorer {

    private ArchiveCompletenessScorer() {}

    public record Score(int filled, int total) {
        public int percent() {
            if (total <= 0) {
                return 100;
            }
            return (int) Math.round(filled * 100.0 / total);
        }

        public Score plus(Score other) {
            return new Score(filled + other.filled, total + other.total);
        }
    }

    public static Score score(List<DictItemDto> fields, JsonNode contentRoot) {
        JsonNode root = contentRoot == null || contentRoot.isNull() ? JsonUtils.emptyObject() : contentRoot;
        int filled = 0;
        int total = 0;
        for (DictItemDto field : fields) {
            if (field == null || field.getDictCode() == null || field.getDictCode().isBlank()) {
                continue;
            }
            JsonNode schema = JsonUtils.readTree(field.getContent());
            String widget = schema.path("widget").asText("TEXT");
            if ("COMPOSITE".equals(widget)) {
                JsonNode composite =
                        root.get(field.getDictCode()) != null && root.get(field.getDictCode()).isObject()
                                ? root.get(field.getDictCode())
                                : JsonUtils.emptyObject();
                JsonNode subFields = schema.path("fields");
                if (subFields.isArray() && !subFields.isEmpty()) {
                    for (JsonNode sub : subFields) {
                        String subCode = sub.path("code").asText(null);
                        if (subCode == null || subCode.isBlank()) {
                            continue;
                        }
                        total++;
                        if (!isEmpty(composite.get(subCode))) {
                            filled++;
                        }
                    }
                    continue;
                }
            }
            total++;
            if (isFieldFilled(field.getDictCode(), root)) {
                filled++;
            }
        }
        return new Score(filled, total);
    }

    static boolean isFieldFilled(String dictCode, JsonNode root) {
        if ("familyHistory".equals(dictCode)) {
            return isFamilyHistoryFilled(root);
        }
        if ("pastHistory".equals(dictCode) || "pastHistoryItems".equals(dictCode)) {
            return isPastHistoryFilled(root, dictCode);
        }
        return !isEmpty(root.get(dictCode));
    }

    /**
     * 家族史：已点选「无/有」即采集完成；兼容仅写了摘要或 items 的旧数据。
     */
    static boolean isFamilyHistoryFilled(JsonNode root) {
        String status = text(root, "familyHistoryStatus");
        if ("none".equals(status) || "has".equals(status)) {
            return true;
        }
        if (!isEmpty(root.get("familyHistory"))) {
            return true;
        }
        return !isEmpty(root.get("familyHistoryItems"));
    }

    /**
     * 既往史：已点选「无/有」即采集完成；兼容摘要 / 结构化明细。
     */
    static boolean isPastHistoryFilled(JsonNode root, String dictCode) {
        String status = text(root, "pastHistoryStatus");
        if ("none".equals(status) || "has".equals(status)) {
            return true;
        }
        if ("pastHistoryItems".equals(dictCode)) {
            return !isEmpty(root.get("pastHistoryItems"));
        }
        return !isEmpty(root.get("pastHistory"));
    }

    static boolean isEmpty(JsonNode node) {
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
            // 兼容 lifestyle.smoking / drinking 嵌套对象：有任一非空子值即视为有值
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                if (!isEmpty(it.next().getValue())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return "";
        }
        return n.asText().trim();
    }
}
