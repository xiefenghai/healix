package com.healix.agent.persona;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PersonaLlmGeneratorTest {

    @Test
    void intakeBrief_nullOrBlank() {
        assertNull(PersonaLlmGenerator.intakeBrief(null));
        assertNull(PersonaLlmGenerator.intakeBrief("  "));
    }

    @Test
    void intakeBrief_truncatesLongSummary() {
        String longText = "患者男，出生于1981年12月。".repeat(20);
        String brief = PersonaLlmGenerator.intakeBrief(longText);
        assertTrue(brief.length() <= 120);
        assertEquals(120, brief.length());
    }

    @Test
    void intakeBrief_keepsShortSummary() {
        assertEquals("短摘要", PersonaLlmGenerator.intakeBrief("短摘要"));
    }

    @Test
    void intakeBrief_alignsAgeWithIdentity() {
        String brief = PersonaLlmGenerator.intakeBrief("男性，约44岁，高血压高危。", 45);
        assertEquals("男性，约45岁，高血压高危。", brief);
        assertEquals("年龄45，需关注。", PersonaLlmGenerator.intakeBrief("年龄44，需关注。", 45));
    }
}
