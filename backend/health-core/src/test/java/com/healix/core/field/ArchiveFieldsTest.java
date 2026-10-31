package com.healix.core.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArchiveFieldsTest {

    private final FieldCatalog catalog = new FieldCatalog();

    @Test
    void flattenAndAssembleRoundTrip() {
        ObjectNode root = JsonUtils.emptyObject();
        root.putObject("diet").put("appetite", "GOOD").put("habit", "REGULAR");
        root.putObject("exercise").put("frequency", "WEEKLY_3").put("durationMin", 30);
        root.putObject("lifestyle").putObject("smoking").put("status", "NEVER");

        Map<String, JsonNode> flat = ArchiveFields.flatten("basic.", root, catalog);
        assertEquals("GOOD", flat.get("basic.diet.appetite").asText());
        assertEquals(30, flat.get("basic.exercise.durationMin").asInt());
        assertEquals("NEVER", flat.get("basic.lifestyle.smoking.status").asText());

        ObjectNode rebuilt = ArchiveFields.assemble("basic.", flat);
        assertEquals("GOOD", rebuilt.path("diet").path("appetite").asText());
        assertEquals(30, rebuilt.path("exercise").path("durationMin").asInt());
        assertEquals("NEVER", rebuilt.path("lifestyle").path("smoking").path("status").asText());
    }

    @Test
    void normalizesLegacySmokingString() {
        ObjectNode root = JsonUtils.emptyObject();
        root.putObject("lifestyle").put("smoking", "CURRENT");

        Map<String, JsonNode> flat = ArchiveFields.flatten("basic.", root, catalog);
        assertEquals("CURRENT", flat.get("basic.lifestyle.smoking.status").asText());
    }

    @Test
    void keepsUncataloguedLeaves() {
        ObjectNode root = JsonUtils.emptyObject();
        root.put("customNote", "keep-me");
        root.putObject("diet").put("appetite", "GOOD").put("weirdExtra", "x");

        Map<String, JsonNode> flat = ArchiveFields.flatten("basic.", root, catalog);
        assertEquals("keep-me", flat.get("basic.customNote").asText());
        // diet 下有登记子字段，继续拆；未登记 weirdExtra 作为叶子保留
        assertEquals("x", flat.get("basic.diet.weirdExtra").asText());
        assertEquals("GOOD", flat.get("basic.diet.appetite").asText());

        ObjectNode rebuilt = ArchiveFields.assemble("basic.", flat);
        assertEquals("keep-me", rebuilt.path("customNote").asText());
        assertEquals("x", rebuilt.path("diet").path("weirdExtra").asText());
    }

    @Test
    void skipsEmptyValues() {
        ObjectNode root = JsonUtils.emptyObject();
        root.putObject("diet").put("appetite", "").put("note", "  ");
        Map<String, JsonNode> flat = ArchiveFields.flatten("basic.", root, catalog);
        assertFalse(flat.containsKey("basic.diet.appetite"));
        assertTrue(flat.isEmpty() || !flat.containsKey("basic.diet.note"));
    }
}
