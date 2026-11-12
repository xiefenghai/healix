package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class CarePlanSampleDaySyncTest {

    @Test
    void syncsSampleDayFromMonday() {
        ObjectNode diet = JsonUtils.emptyObject();
        ObjectNode day = diet.putObject("sampleDay");
        day.put("breakfast", "白米饭约100g");
        day.put("lunch", "清蒸鱼");
        ObjectNode week = diet.putObject("sampleWeek");
        ObjectNode mon = week.putObject("MON");
        mon.put("breakfast", "杂粮饭约150g（熟重）");
        mon.put("lunch", "鸡胸肉约80g");
        mon.put("dinner", "清炒时蔬");
        week.putObject("TUE").put("breakfast", "全麦馒头约70g");

        assertTrue(CarePlanSampleDaySync.syncFromMonday(diet));
        assertEquals("杂粮饭约150g（熟重）", diet.at("/sampleDay/breakfast").asText());
        assertEquals("鸡胸肉约80g", diet.at("/sampleDay/lunch").asText());
        assertEquals("清炒时蔬", diet.at("/sampleDay/dinner").asText());
        assertEquals("杂粮饭约150g（熟重）", diet.at("/sampleWeek/MON/breakfast").asText());
    }

    @Test
    void noOpWhenMondayMissing() {
        ObjectNode diet = JsonUtils.emptyObject();
        diet.putObject("sampleDay").put("breakfast", "燕麦");
        diet.putObject("sampleWeek").putObject("TUE").put("breakfast", "馒头");

        assertFalse(CarePlanSampleDaySync.syncFromMonday(diet));
        assertEquals("燕麦", diet.at("/sampleDay/breakfast").asText());
    }
}
