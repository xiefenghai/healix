package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import java.util.Iterator;
import java.util.Map;
import org.springframework.util.StringUtils;

/** 生成理由：缺失检测、C 端剥离、编辑后清空。 */
public final class CarePlanRationaleSupport {

    public static final String WARN_MISSING = "MISSING_RATIONALE";

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

    /** 递归去掉 rationale 字段（C 端）。 */
    public static JsonNode strip(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.remove("rationale");
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
     * 若新块相对旧块（忽略 rationale）有实质变更，则清空新块及其子树的 rationale。
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
            o.remove("rationale");
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
            return base + "注意：档案未见与专项完全匹配的确诊，请健管师核对。";
        }
        return base;
    }
}
