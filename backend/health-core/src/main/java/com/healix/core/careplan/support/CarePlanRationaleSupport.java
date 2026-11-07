package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** 生成理由：缺失检测、C 端剥离、编辑后清空、编码 scrub。 */
public final class CarePlanRationaleSupport {

    public static final String WARN_MISSING = "MISSING_RATIONALE";

    /**
     * 指南条目编码，如 {@code WL.ENERGY_DEFICIT}、{@code DM.DIET.CARB}、{@code HTN.DIET.SALT}。
     * 应只出现在 {@code refs}，不应写进 rationale 正文。
     */
    private static final Pattern GUIDELINE_CODE =
            Pattern.compile("\\b[A-Z]{2,12}(?:\\.[A-Z][A-Z0-9_]{0,32}){1,6}\\b");

    /** 「按 CODE 与 CODE」整段前缀， scrub 成「按指南」 */
    private static final Pattern GUIDELINE_CODE_PHRASE = Pattern.compile(
            "按\\s*" + GUIDELINE_CODE.pattern() + "(?:\\s*[与和及、,/]\\s*" + GUIDELINE_CODE.pattern() + ")*");

    private CarePlanRationaleSupport() {}

    public static boolean missingBlockOrOverview(
            JsonNode exercise, JsonNode diet, JsonNode execution, JsonNode contextSnapshot) {
        JsonNode overview = contextSnapshot == null ? null : contextSnapshot.get("rationale");
        if (overview == null || !StringUtils.hasText(overview.path("overview").asText(null))) {
            return true;
        }
        return !hasRationale(exercise) || !hasRationale(diet) || !hasRationale(execution);
    }

    public static boolean hasRationale(JsonNode block) {
        return block != null && !block.isNull() && StringUtils.hasText(block.path("rationale").asText(null));
    }

    public static void putOverview(
            ObjectNode snapshot, CarePlanTemplateKeyEnum key, String overview, String... sources) {
        if (snapshot == null) {
            return;
        }
        ObjectNode r = snapshot.putObject("rationale");
        if (key != null) {
            r.put("templateKey", key.canonical().name());
        }
        r.put("overview", overview == null ? "" : overview);
        ArrayNode src = r.putArray("sources");
        if (sources != null) {
            for (String s : sources) {
                if (StringUtils.hasText(s)) {
                    src.add(s);
                }
            }
        }
    }

    /** 仅健管师可见的字段：生成理由、指南引用、条目匹配快照。 */
    private static final List<String> STAFF_ONLY = List.of("rationale", "refs", "guideline");

    /** 递归去掉仅健管师可见的字段（C 端）。 */
    public static JsonNode strip(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.remove(STAFF_ONLY);
            Iterator<Map.Entry<String, JsonNode>> it = copy.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                copy.set(e.getKey(), strip(e.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode arr = JsonUtils.mapper().createArrayNode();
            for (JsonNode child : node) {
                arr.add(strip(child));
            }
            return arr;
        }
        return node;
    }

    /**
     * 若新块相对旧块（忽略 rationale / refs）有实质变更，则清空新块及其子树的 rationale 与 refs。
     */
    public static ObjectNode clearIfContentChanged(JsonNode oldBlock, JsonNode newBlock) {
        if (newBlock == null || !newBlock.isObject()) {
            return newBlock == null || newBlock.isNull()
                    ? JsonUtils.emptyObject()
                    : ((ObjectNode) newBlock);
        }
        ObjectNode neu = ((ObjectNode) newBlock).deepCopy();
        if (oldBlock == null || oldBlock.isNull() || !oldBlock.isObject()) {
            return neu;
        }
        ObjectNode oldCopy = strip(oldBlock).deepCopy();
        ObjectNode newCopy = strip(neu).deepCopy();
        if (!oldCopy.equals(newCopy)) {
            stripInPlace(neu);
        }
        return neu;
    }

    private static void stripInPlace(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            ObjectNode o = (ObjectNode) node;
            o.remove(List.of("rationale", "refs"));
            o.fields().forEachRemaining(e -> stripInPlace(e.getValue()));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                stripInPlace(child);
            }
        }
    }

    public static String defaultOverview(CarePlanTemplateKeyEnum key, boolean specialtyMismatch) {
        CarePlanTemplateKeyEnum c = key == null ? CarePlanTemplateKeyEnum.WELLNESS : key.canonical();
        String base =
                switch (c) {
                    case COMPREHENSIVE -> "按综合管理生成：全维度覆盖运动、饮食与执行，监测任务按档案病种标签叠加。";
                    case DIABETES -> "按糖尿病管理生成：以控糖为主叙事，餐后活动与血糖监测为重点。";
                    case HYPERTENSION -> "按高血压管理生成：以控压为主叙事，有氧与家庭血压监测为重点。";
                    case OBESITY -> "按肥胖管理生成：以体重管理为主叙事，饮食热量与每日体重记录为重点。";
                    case WELLNESS -> "按健康管理生成：未以慢病专项为主，侧重均衡生活方式与基础打卡。";
                    default -> "按健康管理生成。";
                };
        if (specialtyMismatch) {
            return base + "注意：档案未见与专项完全匹配的病种，请健管师核对。";
        }
        return base;
    }

    /**
     * 清洗生成依据：正文去掉条目编码（并入 {@code refs}），overview 同步处理。
     * 便于健管师阅读；编码仍可通过「依据了 N 部权威指南」查看。
     */
    public static void scrubGuidelineCodesInPlan(ObjectNode exercise, ObjectNode diet, ObjectNode execution, ObjectNode snapshot) {
        scrubBlock(exercise);
        scrubBlock(diet);
        scrubBlock(execution);
        if (execution != null && execution.path("tasks").isArray()) {
            for (JsonNode t : execution.get("tasks")) {
                if (t instanceof ObjectNode task) {
                    scrubBlock(task);
                }
            }
        }
        if (snapshot != null && snapshot.path("rationale").isObject()) {
            ObjectNode r = (ObjectNode) snapshot.get("rationale");
            String overview = r.path("overview").asText(null);
            if (StringUtils.hasText(overview)) {
                ScrubResult sr = scrubText(overview);
                r.put("overview", sr.text());
            }
        }
    }

    private static void scrubBlock(ObjectNode block) {
        if (block == null) {
            return;
        }
        String rationale = block.path("rationale").asText(null);
        if (!StringUtils.hasText(rationale)) {
            return;
        }
        ScrubResult sr = scrubText(rationale);
        block.put("rationale", sr.text());
        if (sr.codes().isEmpty()) {
            return;
        }
        ArrayNode refs = block.withArray("refs");
        Set<String> existing = new LinkedHashSet<>();
        for (JsonNode n : refs) {
            if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                existing.add(n.asText().trim());
            }
        }
        for (String code : sr.codes()) {
            if (existing.add(code)) {
                refs.add(code);
            }
        }
    }

    /** 测试与内部复用：去掉「按 CODE」短语与裸编码，返回清洗文案与抽出的 code。 */
    static ScrubResult scrubText(String raw) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher collect = GUIDELINE_CODE.matcher(raw);
        while (collect.find()) {
            codes.add(collect.group());
        }
        String text = GUIDELINE_CODE_PHRASE.matcher(raw).replaceAll("按指南");
        text = GUIDELINE_CODE.matcher(text).replaceAll("");
        text = text.replaceAll("按指南(?:\\s*按指南)+", "按指南");
        text = text.replaceAll("[与和及、,/]{2,}", "、");
        text = text.replaceAll("\\s{2,}", " ");
        text = text.replaceAll("([，,；;、])\\1+", "$1");
        text = text.replaceAll("([。；;])\\s*([。；;])+", "$1");
        text = text.replaceAll("(?<=[，,；;、])\\s+", "");
        text = text.trim().replaceAll("^[与和及、,/]+|[与和及、,/]+$", "");
        return new ScrubResult(text, codes);
    }

    record ScrubResult(String text, Set<String> codes) {}
}
