package com.healix.core.persona.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class PersonaDimensionAggregatorTest {

    @Test
    void appendConstraintHighlight_usesPlanEffectNotLabel() {
        ArrayNode codes = JsonUtils.mapper().createArrayNode();
        ArrayNode notes = JsonUtils.mapper().createArrayNode();
        ObjectNode h = JsonUtils.emptyObject();
        h.put("label", "颈后、腋下等处是否有皮肤发黑、增厚？");
        h.put("severity", "CAUTION");
        h.put("planEffect", "关注胰岛素抵抗相关体征");

        PersonaDimensionAggregator.appendConstraintHighlight(codes, notes, h);

        assertEquals(1, codes.size());
        assertEquals("CAUTION", codes.get(0).asText());
        assertEquals(1, notes.size());
        assertEquals("关注胰岛素抵抗相关体征", notes.get(0).asText());
        assertFalse(notes.get(0).asText().contains("？"));
    }

    @Test
    void appendConstraintHighlight_skipsWhenOnlyLabel() {
        ArrayNode codes = JsonUtils.mapper().createArrayNode();
        ArrayNode notes = JsonUtils.mapper().createArrayNode();
        ObjectNode h = JsonUtils.emptyObject();
        h.put("label", "题干不应出现");
        h.put("severity", "REFER");

        PersonaDimensionAggregator.appendConstraintHighlight(codes, notes, h);

        assertEquals("REFER", codes.get(0).asText());
        assertEquals(0, notes.size());
    }

    @Test
    void appendBehaviorTags_fromIntakeAnswers() {
        ArrayNode diet = JsonUtils.mapper().createArrayNode();
        ArrayNode exercise = JsonUtils.mapper().createArrayNode();
        ArrayNode psych = JsonUtils.mapper().createArrayNode();
        ObjectNode answers = JsonUtils.emptyObject();
        answers.put("basic.diet.takeoutFrequency", "DAILY_MULTI");
        answers.put("basic.diet.cook", "FAMILY");
        answers.putArray("basic.diet.taste").add("SALTY").add("OILY");
        answers.put("basic.exercise.frequency", "NONE");
        answers.put("basic.exercise.sedentaryHours", 9);
        answers.put("basic.weightHistory.expectedPace", "ASAP");
        answers.put("basic.psych.stressEating", "EAT_MORE");

        PersonaDimensionAggregator.appendBehaviorTags(diet, exercise, psych, answers);

        assertTrue(diet.toString().contains("外卖高频"));
        assertTrue(diet.toString().contains("家人做饭"));
        assertTrue(diet.toString().contains("口味偏咸"));
        assertTrue(exercise.toString().contains("几乎不运动"));
        assertTrue(exercise.toString().contains("久坐"));
        assertTrue(psych.toString().contains("求速减重"));
        assertTrue(psych.toString().contains("压力性进食"));
    }
}
