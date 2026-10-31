package com.healix.core.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FieldCatalogTest {

    private final FieldCatalog catalog = new FieldCatalog();

    @Test
    void loadsPlatformCatalog() {
        assertFalse(catalog.version().isBlank());
        assertTrue(catalog.all().size() >= 100);
        assertTrue(catalog.contains("basic.diet.appetite"));
        assertTrue(catalog.contains("profile.gender"));
        assertTrue(catalog.contains("metric.WEIGHT"));
        assertTrue(catalog.require("basic.familyHistory.firstDegree").virtual());
        assertTrue(catalog.require("basic.diet.appetite").legacy());
        assertFalse(catalog.require("basic.psych.emotionalEating").legacy());
    }

    @Test
    void choiceFieldsHaveOptionSets() {
        FieldDef appetite = catalog.require("basic.diet.appetite");
        assertEquals(FieldType.ENUM, appetite.type());
        assertEquals("appetiteLevel", appetite.options());
        assertTrue(catalog.isDictOptionSet("appetiteLevel") || !catalog.catalogOptions("appetiteLevel").isEmpty());
    }

    @Test
    void hasChildrenDetectsNested() {
        assertTrue(catalog.hasChildren("basic.diet"));
        assertTrue(catalog.hasChildren("basic.lifestyle.smoking"));
        assertFalse(catalog.hasChildren("basic.diet.appetite"));
    }
}
