package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import java.util.List;
import org.junit.jupiter.api.Test;

class CarePlanDiabetesDietGuardTest {

    @Test
    void replacePorridgeDinner() {
        assertEquals("杂粮饭约150g（熟重）", CarePlanDiabetesDietGuard.replacePorridgeText("小米粥半碗"));
        assertEquals("杂粮饭约150g（熟重）、清蒸鱼", CarePlanDiabetesDietGuard.replacePorridgeText("小米粥半碗、清蒸鱼"));
        assertEquals("原味燕麦片约40g（少水冲稠）", CarePlanDiabetesDietGuard.replacePorridgeText("燕麦粥"));
        assertFalse(CarePlanDiabetesDietGuard.replacePorridgeText("杂粮饭约150g（熟重）").contains("粥"));
    }

    @Test
    void sanitizeSampleWeekWhenDiabetes() {
        ObjectNode diet = (ObjectNode) JsonUtils.readTree(
                """
                {"limited":[],"sampleDay":{"dinner":{"text":"小米粥半碗、清蒸鱼"}},
                 "sampleWeek":{"MON":{"dinner":{"text":"杂粮粥一碗"}},"THU":{"dinner":{"text":"小米粥半碗"}}}}
                """);
        ObjectNode snap = JsonUtils.emptyObject();
        snap.putArray("diseaseCodes").add("DIABETES");
        List<String> changed = CarePlanDiabetesDietGuard.sanitize(diet, snap, null);
        assertFalse(changed.isEmpty());
        assertEquals("杂粮饭约150g（熟重）、清蒸鱼", diet.at("/sampleDay/dinner/text").asText());
        assertEquals("杂粮饭约150g（熟重）", diet.at("/sampleWeek/MON/dinner/text").asText());
        assertEquals("杂粮饭约150g（熟重）", diet.at("/sampleWeek/THU/dinner/text").asText());
        assertTrue(diet.get("limited").toString().contains("PORRIDGE"));
        assertTrue(diet.path("notes").asText().contains("少选粥类") || diet.path("notes").asText().contains("粥"));
    }

    @Test
    void skipWhenNotDiabetes() {
        ObjectNode diet = (ObjectNode) JsonUtils.readTree(
                """
                {"sampleDay":{"dinner":{"text":"小米粥半碗"}}}
                """);
        assertTrue(CarePlanDiabetesDietGuard.sanitize(diet, JsonUtils.emptyObject(), CarePlanTemplateKeyEnum.HYPERTENSION)
                .isEmpty());
        assertEquals("小米粥半碗", diet.at("/sampleDay/dinner/text").asText());
    }
}
