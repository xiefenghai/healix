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
    void appendConstraintHighlight_usesNoteNotLabelOrPlanEffect() {
        ArrayNode codes = JsonUtils.mapper().createArrayNode();
        ArrayNode notes = JsonUtils.mapper().createArrayNode();
        ObjectNode h = JsonUtils.emptyObject();
        h.put("label", "颈后、腋下等处是否有皮肤发黑、增厚？");
        h.put("severity", "CAUTION");
        h.put("planEffect", "方案加入应酬少饮酒和低盐点餐");
        h.put("note", "关注胰岛素抵抗相关体征");

        PersonaDimensionAggregator.appendConstraintHighlight(codes, notes, h);

        assertEquals(1, codes.size());
        assertEquals("CAUTION", codes.get(0).asText());
        assertEquals(1, notes.size());
        assertEquals("关注胰岛素抵抗相关体征", notes.get(0).asText());
        assertFalse(notes.get(0).asText().contains("？"));
        assertFalse(notes.get(0).asText().contains("方案加入"));
    }

    @Test
    void appendConstraintHighlight_skipsPlanEffectPrompt() {
        ArrayNode codes = JsonUtils.mapper().createArrayNode();
        ArrayNode notes = JsonUtils.mapper().createArrayNode();
        ObjectNode h = JsonUtils.emptyObject();
        h.put("severity", "CAUTION");
        h.put("planEffect", "经常外卖：给能点到的替换，不按自己做饭来写");

        PersonaDimensionAggregator.appendConstraintHighlight(codes, notes, h);

        assertEquals(1, codes.size());
        assertEquals(0, notes.size());
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
    void dedupeConflictingDiseaseTags_dropsPrediabetesWhenDiabetes() {
        ArrayNode tags = JsonUtils.mapper().createArrayNode();
        tags.add("HYPERTENSION");
        tags.add("PREDIABETES");
        tags.add("DIABETES");
        tags.add("BP_ELEVATED");

        ArrayNode out = PersonaDimensionAggregator.dedupeConflictingDiseaseTags(tags);

        assertEquals(2, out.size());
        assertEquals("HYPERTENSION", out.get(0).asText());
        assertEquals("DIABETES", out.get(1).asText());
    }

    @Test
    void dedupeConflictingDiseaseTags_chineseType2DropsPrediabetes() {
        ArrayNode tags = JsonUtils.mapper().createArrayNode();
        tags.add("高血压");
        tags.add("糖尿病前期");
        tags.add("2型糖尿病");

        ArrayNode out = PersonaDimensionAggregator.dedupeConflictingDiseaseTags(tags);

        assertEquals(2, out.size());
        assertEquals("高血压", out.get(0).asText());
        assertEquals("2型糖尿病", out.get(1).asText());
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

    @Test
    void fingerprint_ignoresExecutionFocusAndAggregatedAt() {
        ObjectNode a = JsonUtils.emptyObject();
        a.putObject("identity").put("displayName", "张三");
        a.putObject("risk").putArray("tags").add("HYPERTENSION");
        a.putObject("constraints").putArray("allergens").add("花生");
        a.putObject("execution").put("planRate7d", 0.9);
        a.putObject("focus").put("nextStep", "催打卡");
        a.put("aggregatedAt", "2026-01-01T00:00:00");

        ObjectNode b = a.deepCopy();
        b.with("execution").put("planRate7d", 0.1);
        b.with("focus").put("nextStep", "随访");
        b.put("aggregatedAt", "2026-11-10T12:00:00");

        assertEquals(
                PersonaDimensionAggregator.fingerprint(a), PersonaDimensionAggregator.fingerprint(b));

        b.with("constraints").withArray("allergens").removeAll().add("海鲜");
        assertFalse(
                PersonaDimensionAggregator.fingerprint(a)
                        .equals(PersonaDimensionAggregator.fingerprint(b)));
    }
}
