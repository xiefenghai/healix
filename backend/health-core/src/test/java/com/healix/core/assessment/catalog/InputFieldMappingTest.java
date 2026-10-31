package com.healix.core.assessment.catalog;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.field.FieldCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

class InputFieldMappingTest {

    private final FieldCatalog catalog = new FieldCatalog();

    @Test
    void everyLegacyInputFieldHasCodesInCatalog() {
        assertFalse(InputFieldMapping.legacyNames().isEmpty());
        for (String legacy : InputFieldMapping.legacyNames()) {
            List<String> codes = InputFieldMapping.codesOf(legacy);
            assertFalse(codes.isEmpty(), legacy + " 缺少字段编码");
            for (String code : codes) {
                assertTrue(catalog.contains(code), legacy + " → " + code + " 不在 FieldCatalog");
            }
        }
    }

    @Test
    void convertFieldListMapsLegacyNames() {
        List<String> converted = InputFieldMapping.convertFieldList(List.of("HEIGHT", "BLOOD_PRESSURE", "metric.WAIST"));
        assertTrue(converted.contains(AssessmentFields.HEIGHT));
        assertTrue(converted.contains(AssessmentFields.BLOOD_PRESSURE_SYS));
        assertTrue(converted.contains(AssessmentFields.BLOOD_PRESSURE_DIA));
        assertTrue(converted.contains(AssessmentFields.WAIST));
    }
}
