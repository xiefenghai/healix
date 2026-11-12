package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.water.support.WaterIntakeRules;
import org.junit.jupiter.api.Test;

class CarePlanExecutionTaskNormalizerTest {

    @Test
    void splitsDietTitleWithMedicationCue() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode diet = tasks.addObject();
        diet.put("code", "DIET_LOG");
        diet.put("category", "DIET");
        diet.put("title", "记录三餐与加餐，服药与第一口饭同服");
        diet.put("frequency", "QD");
        diet.put("enabled", true);

        ObjectNode snap = JsonUtils.emptyObject();
        ArrayNode meds = snap.putArray("activeMedicationDetails");
        ObjectNode med = meds.addObject();
        med.put("name", "阿卡波糖");
        med.put("timingNote", "与第一口饭同服");
        med.put("frequency", "TID");

        CarePlanExecutionTaskNormalizer.normalize(exec, snap);

        assertEquals("记录三餐与加餐", diet.path("title").asText());
        boolean foundMed = false;
        for (JsonNode t : tasks) {
            if ("MED_REMINDER".equals(t.path("code").asText())) {
                foundMed = true;
                assertEquals("MEDICATION", t.path("category").asText());
                assertTrue(t.path("title").asText().contains("阿卡波糖"));
                assertEquals("TID", t.path("frequency").asText());
            }
        }
        assertTrue(foundMed);
    }

    @Test
    void waterTargetUsesWeightNotLegacyCap() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode water = tasks.addObject();
        water.put("code", "WATER_LOG");
        water.put("category", "WATER");
        water.put("title", "足量饮水，每日约 2000 ml");
        water.put("targetMl", 2000);
        water.put(
                "rationale",
                "按体重约82kg×30估算，落在1200-2000ml上限，少量多次饮水");

        ObjectNode snap = JsonUtils.emptyObject();
        ArrayNode metrics = snap.putArray("latestMetrics");
        ObjectNode w = metrics.addObject();
        w.put("metricType", "WEIGHT");
        w.put("value", "82");

        CarePlanExecutionTaskNormalizer.normalize(exec, snap);

        assertEquals(2500, water.path("targetMl").asInt());
        assertEquals(WaterIntakeRules.suggestTargetMl(82.0), water.path("targetMl").asInt());
        String rationale = water.path("rationale").asText();
        assertTrue(rationale.contains("2500"));
        assertFalse(rationale.contains("落在"));
        assertFalse(rationale.contains("上限"));
    }

    @Test
    void sedentaryBreakUsesHabitFrequency() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode sit = tasks.addObject();
        sit.put("code", "EX_SIT_BREAK");
        sit.put("category", "EXERCISE");
        sit.put("title", "久坐每小时起身活动3~5分钟");
        sit.put("frequency", "QD");
        sit.put("timeSlot", "ALL");
        sit.put("enabled", true);

        CarePlanExecutionTaskNormalizer.normalize(exec, null);

        assertEquals("HABIT", sit.path("frequency").asText());
        assertEquals("ALL", sit.path("timeSlot").asText());
        assertEquals("SEDENTARY_BREAK", sit.path("relatedRef").asText());
    }

    @Test
    void backfillsSleepAndMoodWhenMissing() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode diet = tasks.addObject();
        diet.put("code", "DIET_LOG");
        diet.put("category", "DIET");
        diet.put("title", "记录三餐与加餐");
        diet.put("frequency", "QD");
        diet.put("enabled", true);

        CarePlanExecutionTaskNormalizer.normalize(exec, null);

        boolean sleep = false;
        boolean mood = false;
        for (JsonNode t : tasks) {
            if ("SLEEP_LOG".equals(t.path("code").asText())) {
                sleep = true;
                assertEquals("SLEEP", t.path("category").asText());
                assertEquals("QD", t.path("frequency").asText());
            }
            if ("MOOD_LOG".equals(t.path("code").asText())) {
                mood = true;
                assertEquals("MOOD", t.path("category").asText());
            }
        }
        assertTrue(sleep);
        assertTrue(mood);
    }

    @Test
    void doesNotDuplicateExistingSleepOrMood() {
        ObjectNode exec = JsonUtils.emptyObject();
        ArrayNode tasks = exec.putArray("tasks");
        ObjectNode sleep = tasks.addObject();
        sleep.put("code", "SLEEP_LOG");
        sleep.put("category", "SLEEP");
        sleep.put("title", "自定义睡眠记录");
        sleep.put("frequency", "QD");
        sleep.put("enabled", true);
        ObjectNode mood = tasks.addObject();
        mood.put("code", "MOOD_LOG");
        mood.put("category", "MOOD");
        mood.put("title", "自定义心情");
        mood.put("frequency", "QD");
        mood.put("enabled", true);

        CarePlanExecutionTaskNormalizer.normalize(exec, null);

        int sleepCount = 0;
        int moodCount = 0;
        for (JsonNode t : tasks) {
            if ("SLEEP_LOG".equals(t.path("code").asText())) {
                sleepCount++;
            }
            if ("MOOD_LOG".equals(t.path("code").asText())) {
                moodCount++;
            }
        }
        assertEquals(1, sleepCount);
        assertEquals(1, moodCount);
        assertEquals("自定义睡眠记录", sleep.path("title").asText());
    }
}
