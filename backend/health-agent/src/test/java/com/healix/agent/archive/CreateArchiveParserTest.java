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
    void parseShortYearNaturalTalk() {
        CreateArchiveDraft d =
                CreateArchiveParser.parse("给张三建个档，女的，90年5月1日出生，电话13800138000");
        assertEquals("张三", d.name());
        assertEquals("FEMALE", d.gender());
        assertEquals(LocalDate.of(1990, 5, 1), d.birthday());
        assertEquals("13800138000", d.mobile());
        assertTrue(d.isComplete());
    }

    @Test
    void yearMonthWithoutDayDoesNotGuess() {
        CreateArchiveDraft d = CreateArchiveParser.parse("李敏 女 1990年5月出生 13800138000");
        assertEquals("李敏", d.name());
        assertEquals("FEMALE", d.gender());
        assertEquals(null, d.birthday());
        assertEquals("13800138000", d.mobile());
        assertFalse(d.isComplete());
    }

    @Test
    void mergeCompletesAcrossTurns() {
        CreateArchiveDraft a = CreateArchiveParser.parse("帮我建档");
        assertFalse(a.isComplete());
        CreateArchiveDraft b = CreateArchiveParser.merge(a, CreateArchiveParser.parse("王芳 女 1992-11-02 13700001111"));
        assertTrue(b.isComplete());
        assertEquals("王芳", b.name());
    }

    @Test
    void placeholderLabeledFormDoesNotFill() {
        CreateArchiveDraft d =
                CreateArchiveParser.parse(
                        "1.姓名：待补充\n2.性别：待补充\n3.出生：待补充\n4.手机：待补充");
        assertFalse(d.hasName());
        assertFalse(d.hasGender());
        assertFalse(d.hasBirthday());
        assertFalse(d.hasMobile());
        assertFalse(d.isComplete());
    }

    @Test
    void placeholderLabeledFormInlineDoesNotFill() {
        CreateArchiveDraft d =
                CreateArchiveParser.parse("1.姓名：待补充2.性别：待补充3.出生：待补充4.手机：待补充");
        assertFalse(d.hasName());
        assertFalse(d.hasGender());
        assertFalse(d.hasBirthday());
        assertFalse(d.hasMobile());
    }

    @Test
    void labeledFormPaste() {
        CreateArchiveDraft d =
                CreateArchiveParser.parse(
                        "姓名：李大牛\n性别：男\n出生：1990-05-01\n手机：13800138000");
        assertEquals("李大牛", d.name());
        assertEquals("MALE", d.gender());
        assertEquals(LocalDate.of(1990, 5, 1), d.birthday());
        assertEquals("13800138000", d.mobile());
        assertTrue(d.isComplete());
    }

    @Test
    void parseMobileFirstWithAgeYears() {
        CreateArchiveDraft d = CreateArchiveParser.parse("18758222123 李默 35岁 男");
        assertEquals("李默", d.name());
        assertEquals("MALE", d.gender());
        assertEquals("18758222123", d.mobile());
        int expectYear = LocalDate.now().getYear() - 35;
        assertEquals(LocalDate.of(expectYear, 1, 1), d.birthday());
        assertTrue(d.isComplete());
    }

    @Test
    void noiseName分组IsRejected() {
        assertTrue(CreateArchiveParser.isNoiseName("分组"));
        assertTrue(CreateArchiveParser.isNoiseName("待分组"));
        assertTrue(CreateArchiveParser.isNoiseName("建组"));
        CreateArchiveDraft d = CreateArchiveParser.parse("分组 男 1990-01-01 13800138000");
        assertFalse(d.hasName());
    }
}
