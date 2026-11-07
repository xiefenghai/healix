package com.healix.core.careplan.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

class CarePlanDietNotesGuardTest {

    @Test
    void stripsStaffAllergyQcAndSplits() {
        ObjectNode diet = JsonUtils.emptyObject();
        diet.put(
                "notes",
                "降压药需随第一口饭同服，不要空腹服药。先吃蔬菜和蛋白质，再吃主食；外出就餐主动要求少盐少油；过敏忌口尚未采集，如已知对海鲜过敏请告知。");
        CarePlanDietNotesGuard.sanitize(diet);
        String notes = diet.path("notes").asText();
        assertFalse(notes.contains("过敏忌口"));
        assertFalse(notes.contains("医嘱"));
        assertTrue(notes.contains("进餐顺序"));
        assertTrue(notes.contains("服药配合") || notes.contains("第一口饭"));
        assertTrue(notes.contains("外食技巧") || notes.contains("少盐少油"));
    }
}
