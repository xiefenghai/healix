package com.healix.core.water.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class WaterTaskMigrationTest {

    @Test
    void migratesLegacyDietWaterTask() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode water = tasks.addObject();
        water.put("code", "DIET_WATER");
        water.put("title", "足量饮水，每日约 1600 ml");
        water.put("category", "DIET");
        ObjectNode diet = tasks.addObject();
        diet.put("code", "DIET_LOG");
        diet.put("title", "记录三餐");
        diet.put("category", "DIET");

        ObjectNode out = WaterTaskMigration.normalizeExecution(exec);
        ObjectNode migrated = (ObjectNode) out.get("tasks").get(0);
        assertEquals("WATER", migrated.path("category").asText());
        assertEquals("WATER_LOG", migrated.path("code").asText());
        assertEquals(1600, migrated.path("targetMl").asInt());
        assertEquals(1600, migrated.path("payload").path("targetMl").asInt());

        ObjectNode untouched = (ObjectNode) out.get("tasks").get(1);
        assertEquals("DIET", untouched.path("category").asText());
        assertEquals("DIET_LOG", untouched.path("code").asText());
        assertFalse(untouched.has("targetMl"));
    }
}
