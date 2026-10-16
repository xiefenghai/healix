package com.healix.core.assessment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.InputField;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AssessmentQuestionnaireServiceTest {

    private static ObjectNode archive(String json) {
        return (ObjectNode) JsonUtils.readTree(json);
    }

    private static List<String> diseasesOf(JsonNode item) {
        List<String> out = new ArrayList<>();
        item.path("diseases").forEach(d -> out.add(d.asText()));
        return out;
    }

    @Test
    void familyHistoryKeepsOtherLevelsAndAddsUnspecified() {
        ObjectNode root = archive("""
                {"familyHistoryStatus":"has","familyHistoryItems":[
                  {"kinshipLevel":"1","member":"FATHER","diseases":["FH_DIABETES","FH_CANCER"]},
                  {"kinshipLevel":"2","member":"GRANDPA","diseases":["FH_DIABETES"]}
                ]}""");
        AssessmentQuestionnaireService.applyFamilyHistory(root, List.of("FH_HYPERTENSION"));
        JsonNode items = root.get("familyHistoryItems");
        assertEquals(3, items.size());
        assertEquals(List.of("FH_CANCER"), diseasesOf(items.get(0)));
        assertEquals("2", items.get(1).path("kinshipLevel").asText());
        assertEquals("UNSPECIFIED", items.get(2).path("member").asText());
        assertEquals(List.of("FH_HYPERTENSION"), diseasesOf(items.get(2)));
        assertEquals("has", root.path("familyHistoryStatus").asText());
    }

    @Test
    void familyHistoryNoneClearsFirstDegree() {
        ObjectNode root = archive("""
                {"familyHistoryItems":[{"kinshipLevel":"1","member":"MOTHER","diseases":["FH_DIABETES"]}]}""");
        AssessmentQuestionnaireService.applyFamilyHistory(root, List.of("NONE"));
        assertEquals(0, root.get("familyHistoryItems").size());
        assertEquals("none", root.path("familyHistoryStatus").asText());
    }

    @Test
    void presentIllnessOnlyReplacesQuestionnaireOptions() {
        ObjectNode root = archive("""
                {"presentIllness":["DIABETES","CHD","GOUT"]}""");
        AssessmentQuestionnaireService.applyPresentIllness(root, List.of("NAFLD"));
        List<String> out = new ArrayList<>();
        root.get("presentIllness").forEach(n -> out.add(n.asText()));
        assertEquals(List.of("DIABETES", "GOUT", "NAFLD"), out);
    }

    @Test
    void sameValueComparesNumbersAndSets() {
        assertTrue(AssessmentQuestionnaireService.sameValue(new BigDecimal("170.0"), new BigDecimal("170")));
        assertTrue(AssessmentQuestionnaireService.sameValue(List.of("A", "B"), List.of("B", "A")));
        assertFalse(AssessmentQuestionnaireService.sameValue("NEVER", "DAILY"));
    }

    @Test
    void answerTextUsesOptionLabels() {
        assertEquals("糖尿病、高血压",
                AssessmentQuestionnaireService.answerText(InputField.FAMILY_HISTORY, List.of("FH_DIABETES", "FH_HYPERTENSION")));
        assertEquals("170 cm", AssessmentQuestionnaireService.answerText(InputField.HEIGHT, new BigDecimal("170.0")));
    }
}
