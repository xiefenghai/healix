package com.healix.core.water.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;

/**
 * G47：旧 DIET 饮水任务在保存/发布时迁为 WATER + WATER_LOG，并补齐 targetMl。
 */
public final class WaterTaskMigration {

    private WaterTaskMigration() {}

    /** 返回规范化后的 execution（深拷贝）；入参可空。 */
    public static ObjectNode normalizeExecution(JsonNode execution) {
        ObjectNode exec =
                execution != null && execution.isObject()
                        ? ((ObjectNode) execution).deepCopy()
                        : JsonUtils.emptyObject();
        JsonNode tasksNode = exec.get("tasks");
        if (tasksNode == null || !tasksNode.isArray()) {
            return exec;
        }
        ArrayNode tasks = (ArrayNode) tasksNode;
        for (int i = 0; i < tasks.size(); i++) {
            JsonNode n = tasks.get(i);
            if (!(n instanceof ObjectNode task)) {
                continue;
            }
            migrateTask(task);
        }
        return exec;
    }

    static void migrateTask(ObjectNode task) {
        String category = task.path("category").asText("");
        String title = task.path("title").asText("");
        String code = task.path("code").asText("");
        if (!WaterIntakeRules.looksLikeWaterTask(category, title, code)) {
            return;
        }
        task.put("category", WaterIntakeRules.CATEGORY);
        task.put("code", WaterIntakeRules.TASK_CODE);
        int targetMl = resolveTargetMl(task, title);
        task.put("targetMl", targetMl);
        JsonNode payload = task.get("payload");
        ObjectNode payloadObj =
                payload != null && payload.isObject()
                        ? ((ObjectNode) payload).deepCopy()
                        : JsonUtils.emptyObject();
        payloadObj.put("targetMl", targetMl);
        task.set("payload", payloadObj);
    }

    private static int resolveTargetMl(ObjectNode task, String title) {
        if (task.has("targetMl") && task.get("targetMl").canConvertToInt()) {
            int ml = task.get("targetMl").asInt();
            if (ml > 0) {
                return ml;
            }
        }
        JsonNode payload = task.get("payload");
        if (payload != null && payload.has("targetMl") && payload.get("targetMl").canConvertToInt()) {
            int ml = payload.get("targetMl").asInt();
            if (ml > 0) {
                return ml;
            }
        }
        Integer fromTitle = WaterTaskTargetResolver.parseTargetFromTitle(title);
        return fromTitle != null ? fromTitle : WaterIntakeRules.DEFAULT_TARGET_ML;
    }
}
