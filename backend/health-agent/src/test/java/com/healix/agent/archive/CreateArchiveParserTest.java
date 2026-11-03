package com.healix.agent.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CreateArchiveParserTest {

    @Test
    void parseFullSentence() {
        CreateArchiveDraft d = CreateArchiveParser.parse("建档 李敏 女 1990-05-01 13800138000");
        assertEquals("李敏", d.name());
        assertEquals("FEMALE", d.gender());
        assertEquals(LocalDate.of(1990, 5, 1), d.birthday());
        assertEquals("13800138000", d.mobile());
        assertTrue(d.isComplete());
    }

    @Test
    void parseChineseBirthday() {
        CreateArchiveDraft d = CreateArchiveParser.parse("新建患者张伟 男 1985年3月8日 13912345678");
        assertEquals("张伟", d.name());
        assertEquals("MALE", d.gender());
        assertEquals(LocalDate.of(1985, 3, 8), d.birthday());
        assertTrue(d.isComplete());
    }

    @Test
    void mergeCompletesAcrossTurns() {
        CreateArchiveDraft a = CreateArchiveParser.parse("帮我建档");
        assertFalse(a.isComplete());
        CreateArchiveDraft b = CreateArchiveParser.merge(a, CreateArchiveParser.parse("王芳 女 1992-11-02 13700001111"));
        assertTrue(b.isComplete());
        assertEquals("王芳", b.name());
    }
}
