package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 糖尿病饮食硬约束：示例餐禁止粥类主食（GI 高，晚餐尤险），并强制写入限制项。
 */
public final class CarePlanDiabetesDietGuard {

    public static final String LIMITED_CODE = "PORRIDGE";
    /** 强调「软烂粥」形态，避免与推荐的固态杂粮饭混淆 */
    public static final String LIMITED_LABEL = "熬煮软烂的粥类（相对杂粮饭升糖更快：小米粥/杂粮粥/白米粥等）";

    private static final Pattern HAS_PORRIDGE = Pattern.compile("粥|稀饭");

    private CarePlanDiabetesDietGuard() {}

    /** 快照 / 模板 / 病种是否需要禁粥。 */
    public static boolean applies(
            JsonNode contextSnapshot, CarePlanTemplateKeyEnum templateKey, List<String> diseaseCodes) {
        if (templateKey == CarePlanTemplateKeyEnum.DIABETES) {
            return true;
        }
        if (diseaseCodes != null) {
            for (String c : diseaseCodes) {
                if (c != null && "DIABETES".equalsIgnoreCase(c.trim())) {
                    return true;
                }
            }
        }
        if (contextSnapshot == null || contextSnapshot.isNull()) {
            return false;
        }
        if (codesContain(contextSnapshot.path("diseaseCodes"), "DIABETES")) {
            return true;
        }
        if (codesContain(contextSnapshot.path("goals").path("careTracks"), "DIABETES")) {
            return true;
        }
        if (codesContain(contextSnapshot.path("goals").path("prefs").path("careTracks"), "DIABETES")) {
            return true;
        }
        JsonNode dm = contextSnapshot.get("diabetesClinical");
        if (dm != null && dm.isObject() && !dm.isEmpty()) {
            return true;
        }
        String tk = contextSnapshot.path("templateKey").asText("");
        return "DIABETES".equalsIgnoreCase(tk);
    }

    public static boolean applies(JsonNode contextSnapshot, CarePlanTemplateKeyEnum templateKey) {
        return applies(contextSnapshot, templateKey, null);
    }

    /**
     * 清洗示例日/周中的粥类，并确保 limited 含粥类限制。
     *
     * @return 被替换内容的可读描述
     */
    public static List<String> sanitize(
            ObjectNode diet,
            JsonNode contextSnapshot,
            CarePlanTemplateKeyEnum templateKey,
            List<String> diseaseCodes) {
        List<String> changed = new ArrayList<>();
        if (diet == null || !applies(contextSnapshot, templateKey, diseaseCodes)) {
            return changed;
        }
        ensureLimited(diet);
        sanitizeSampleMeals(diet.get("sampleDay"), "", changed);
        JsonNode week = diet.get("sampleWeek");
        if (week instanceof ObjectNode weekObj) {
            List<String> days = new ArrayList<>();
            weekObj.fieldNames().forEachRemaining(days::add);
            for (String day : days) {
                sanitizeSampleMeals(weekObj.get(day), day + "·", changed);
            }
        }
        ensurePorridgeNote(diet);
        return changed;
    }

    public static List<String> sanitize(
            ObjectNode diet, JsonNode contextSnapshot, CarePlanTemplateKeyEnum templateKey) {
        return sanitize(diet, contextSnapshot, templateKey, null);
    }

    /** 无上下文时仅按模板键判断（模板兜底）。 */
    public static List<String> sanitize(ObjectNode diet, CarePlanTemplateKeyEnum templateKey) {
        return sanitize(diet, null, templateKey, null);
    }

    static String replacePorridgeText(String text) {
        if (!StringUtils.hasText(text) || !HAS_PORRIDGE.matcher(text).find()) {
            return text;
        }
        String out = text;
        out = out.replaceAll("皮蛋瘦肉粥[^、，,\\n；;]*", "杂粮饭约150g（熟重）配瘦肉丝");
        out = out.replaceAll("小米粥[^、，,\\n；;]*", "杂粮饭约150g（熟重）");
        out = out.replaceAll("杂粮粥[^、，,\\n；;]*", "杂粮饭约150g（熟重）");
        out = out.replaceAll("燕麦粥[^、，,\\n；;]*", "原味燕麦片约40g（少水冲稠）");
        out = out.replaceAll("南瓜粥[^、，,\\n；;]*", "蒸南瓜约100g");
        out = out.replaceAll("八宝粥[^、，,\\n；;]*", "杂粮饭约150g（熟重）");
        out = out.replaceAll("(?:白米|大米|白)?粥[^、，,\\n；;]*", "杂粮饭约150g（熟重）");
        out = out.replaceAll("稀饭[^、，,\\n；;]*", "杂粮饭约150g（熟重）");
        // 残留「xx粥」
        out = out.replaceAll("[\\u4e00-\\u9fff]{0,6}粥[^、，,\\n；;]*", "杂粮饭约150g（熟重）");
        return out.trim();
    }

    private static void sanitizeSampleMeals(JsonNode sample, String prefix, List<String> changed) {
        if (!(sample instanceof ObjectNode sampleObj)) {
            return;
        }
        List<String> keys = new ArrayList<>();
        sampleObj.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            JsonNode v = sampleObj.get(key);
            if (v == null || v.isNull()) {
                continue;
            }
            String meal = prefix + key;
            if (v.isTextual()) {
                String raw = v.asText();
                String cleaned = replacePorridgeText(raw);
                if (!cleaned.equals(raw)) {
                    sampleObj.put(key, cleaned);
                    changed.add(meal + "：「" + abbreviate(raw) + "」→「" + abbreviate(cleaned) + "」");
                }
                continue;
            }
            if (v instanceof ObjectNode mealObj) {
                if (mealObj.has("text") && mealObj.get("text").isTextual()) {
                    String raw = mealObj.get("text").asText();
                    String cleaned = replacePorridgeText(raw);
                    if (!cleaned.equals(raw)) {
                        mealObj.put("text", cleaned);
                        changed.add(meal + "：「" + abbreviate(raw) + "」→「" + abbreviate(cleaned) + "」");
                    }
                }
                if (mealObj.has("swapHint") && mealObj.get("swapHint").isTextual()) {
                    String hint = mealObj.get("swapHint").asText();
                    String cleaned = replacePorridgeText(hint);
                    if (!cleaned.equals(hint)) {
                        mealObj.put("swapHint", cleaned);
                    }
                }
            }
        }
    }

    private static void ensureLimited(ObjectNode diet) {
        ArrayNode limited;
        JsonNode n = diet.get("limited");
        if (n instanceof ArrayNode arr) {
            limited = arr;
        } else {
            limited = diet.putArray("limited");
        }
        for (JsonNode item : limited) {
            String code = item.path("code").asText("").toUpperCase(Locale.ROOT);
            String label = item.path("label").asText("");
            if (LIMITED_CODE.equals(code) || label.contains("粥")) {
                return;
            }
        }
        ObjectNode row = limited.addObject();
        row.put("code", LIMITED_CODE);
        row.put("label", LIMITED_LABEL);
    }

    private static void ensurePorridgeNote(ObjectNode diet) {
        String notes = diet.path("notes").asText("");
        if (notes.contains("少喝粥") || notes.contains("避免粥")) {
            return;
        }
        String tip = "主食少选熬煮软烂的粥（越烂升糖越快）；优先杂粮饭、荞麦面等固态粗粮，与推荐主食不矛盾。";
        if (StringUtils.hasText(notes)) {
            diet.put("notes", notes.endsWith("。") || notes.endsWith("；") ? notes + tip : notes + "。" + tip);
        } else {
            diet.put("notes", tip);
        }
    }

    private static boolean codesContain(JsonNode node, String code) {
        if (node == null || node.isNull() || !StringUtils.hasText(code)) {
            return false;
        }
        String want = code.trim().toUpperCase(Locale.ROOT);
        if (node.isArray()) {
            for (JsonNode x : node) {
                if (x != null && want.equalsIgnoreCase(x.asText("").trim())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isTextual()) {
            return want.equalsIgnoreCase(node.asText().trim());
        }
        return false;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 24 ? t : t.substring(0, 24) + "…";
    }
}
