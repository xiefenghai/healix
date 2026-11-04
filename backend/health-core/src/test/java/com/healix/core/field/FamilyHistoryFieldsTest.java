package com.healix.core.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.healix.common.util.JsonUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FamilyHistoryFieldsTest {

    @Test
    void readAndExpandRoundTrip() {
        ArrayNode items = JsonUtils.mapper().createArrayNode();
        items.addObject()
                .put("kinshipLevel", "1")
                .put("member", "FATHER")
                .putArray("diseases")
                .add("DIABETES")
                .add("HYPERTENSION");

        Map<String, JsonNode> current = new LinkedHashMap<>();
        current.put(FamilyHistoryFields.ITEMS, items);
        current.put(FamilyHistoryFields.STATUS, JsonUtils.mapper().getNodeFactory().textNode("has"));

        JsonNode virtual = FamilyHistoryFields.read(current);
        assertEquals(2, virtual.size());

        ArrayNode picked = JsonUtils.mapper().createArrayNode();
        picked.add("DIABETES");
        Map<String, JsonNode> written = FamilyHistoryFields.expandWrite(picked, current);
        assertEquals("has", written.get(FamilyHistoryFields.STATUS).asText());
        assertTrue(written.get(FamilyHistoryFields.ITEMS).toString().contains("DIABETES"));
        assertTrue(!written.get(FamilyHistoryFields.ITEMS).toString().contains("HYPERTENSION")
                || written.get(FamilyHistoryFields.ITEMS).toString().indexOf("HYPERTENSION") < 0);
    }

    @Test
    void noneClearsItems() {
        Map<String, JsonNode> current = new LinkedHashMap<>();
        ArrayNode none = JsonUtils.mapper().createArrayNode();
        none.add("NONE");
        Map<String, JsonNode> written = FamilyHistoryFields.expandWrite(none, current);
        assertEquals("none", written.get(FamilyHistoryFields.STATUS).asText());
    }
}
