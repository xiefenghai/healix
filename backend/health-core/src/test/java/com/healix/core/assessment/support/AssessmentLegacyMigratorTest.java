package com.healix.core.assessment.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.AssessmentFields;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssessmentLegacyMigratorTest {

    @Test
    void convertFieldsJsonMapsLegacyNames() {
        String out = AssessmentLegacyMigrator.convertFieldsJson("[\"HEIGHT\",\"BLOOD_PRESSURE\"]");
        assertTrue(out.contains(AssessmentFields.HEIGHT));
        assertTrue(out.contains(AssessmentFields.BLOOD_PRESSURE_SYS));
        assertTrue(out.contains(AssessmentFields.BLOOD_PRESSURE_DIA));
    }

    @Test
    void convertAnswersJsonConvertsDrinkingAndHighSalt() {
        String out = AssessmentLegacyMigrator.convertAnswersJson(
                "{\"DRINKING\":\"WEEKLY_3\",\"HIGH_SALT\":\"YES\",\"HEIGHT\":170}");
        JsonNode n = JsonUtils.readTree(out);
        assertEquals("CURRENT", n.path(AssessmentFields.DRINKING_STATUS).asText());
        assertEquals("WEEKLY_3", n.path(AssessmentFields.DRINKING_FREQUENCY).asText());
        assertTrue(n.path(AssessmentFields.HIGH_SALT).isArray());
        assertEquals("SALTY", n.path(AssessmentFields.HIGH_SALT).get(0).asText());
        assertEquals(170, n.path(AssessmentFields.HEIGHT).asInt());
    }

    @Test
    void convertFactPrediabetesAndMedication() {
        Map<String, JsonNode> pre = AssessmentLegacyMigrator.convertFact(
                "PREDIABETES", JsonUtils.readTree("{\"value\":\"YES\"}"));
        assertEquals("PREDIABETES", pre.get(AssessmentFields.PRESENT_ILLNESS).get(0).asText());

        Map<String, JsonNode> med = AssessmentLegacyMigrator.convertFact(
                "STATIN", JsonUtils.readTree("{\"value\":\"YES\"}"));
        assertEquals("STATIN", med.get(AssessmentFields.MEDICATION_FLAGS).get(0).asText());
    }

    @Test
    void mergeMultiUnionsCodes() {
        JsonNode merged = AssessmentLegacyMigrator.mergeMulti(
                JsonUtils.readTree("[\"DIABETES\"]"), JsonUtils.readTree("[\"PREDIABETES\",\"DIABETES\"]"));
        assertEquals(2, merged.size());
    }
}
