package com.healix.core.questionnaire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import com.healix.core.field.FieldCatalog;
import org.junit.jupiter.api.Test;

class QuestionnaireCatalogTest {

    private final FieldCatalog fields = new FieldCatalog();
    private final QuestionnaireCatalog catalog = new QuestionnaireCatalog(fields);

    @Test
    void loadsWlIntake() {
        QuestionnaireDef q = catalog.latest("WL_INTAKE");
        assertEquals(1, q.version());
        assertEquals("WEIGHT_LOSS", q.program());
        assertFalse(catalog.itemsOf(q).isEmpty());
        assertTrue(catalog.itemsOf(q).stream().anyMatch(i -> "REQUIRED".equals(i.level())));
        assertTrue(catalog.module("WL_RISK_SCREEN", 1).isPresent());
    }

    @Test
    void showIfGender() {
        ModuleItem women =
                catalog.requireModule("WOMEN_HEALTH", 1).items().stream()
                        .filter(i -> i.field().equals("basic.women.reproductiveStatus"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(women.showIf().matches(c -> "profile.gender".equals(c) ? TextNode.valueOf("FEMALE") : null));
        assertFalse(women.showIf().matches(c -> "profile.gender".equals(c) ? TextNode.valueOf("MALE") : null));
    }

    @Test
    void highlightEatingDisorder() {
        ModuleItem item =
                catalog.requireModule("WL_RISK_SCREEN", 1).items().stream()
                        .filter(i -> i.field().equals("basic.psych.eatingDisorderHistory"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(item.highlights().isEmpty());
        assertTrue(item.highlights().stream().anyMatch(h -> h.matches(TextNode.valueOf("YES"))));
        assertTrue(item.highlights().stream().anyMatch(h -> "REFER".equals(h.severity())));
    }
}
