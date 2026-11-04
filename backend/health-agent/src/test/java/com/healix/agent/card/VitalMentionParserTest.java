package com.healix.agent.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class VitalMentionParserTest {

    @Test
    void bloodPressureDefaultsToHome() {
        VitalMentionParser.Outcome out = VitalMentionParser.parse("血压 165/98");
        assertTrue(out.handled());
        assertEquals(1, out.cards().size());
        VitalCardPayload card = out.cards().get(0);
        assertEquals("BP", card.getMetric());
        assertEquals(new BigDecimal("165"), card.getSys());
        assertEquals(new BigDecimal("98"), card.getDia());
        assertEquals("HOME", card.getBpContext());
        assertEquals("记一次家庭血压 165/98，时间是现在？", VitalCardTexts.summary(card));
    }

    @Test
    void clinicAndFastingFollowTheWords() {
        VitalCardPayload bp = VitalMentionParser.parse("诊室血压 165/98").cards().get(0);
        assertEquals("CLINIC", bp.getBpContext());
        VitalCardPayload glucose = VitalMentionParser.parse("空腹血糖 7.2").cards().get(0);
        assertEquals("FASTING", glucose.getMealContext());
        assertEquals("RANDOM", VitalMentionParser.parse("血糖 7.2").cards().get(0).getMealContext());
        assertEquals("POSTPRANDIAL", VitalMentionParser.parse("餐后血糖 8.1").cards().get(0).getMealContext());
    }

    @Test
    void jinConvertsAndBareNumberStaysKilograms() {
        VitalCardPayload jin = VitalMentionParser.parse("140 斤").cards().get(0);
        assertEquals(new BigDecimal("70.0"), jin.getValue());
        assertEquals(new BigDecimal("140"), jin.getJinRaw());
        assertEquals("记一次体重 70.0 kg（140 斤），时间是现在？", VitalCardTexts.summary(jin));

        VitalCardPayload kg = VitalMentionParser.parse("体重 140").cards().get(0);
        assertEquals(new BigDecimal("140"), kg.getValue());
        assertEquals(null, kg.getJinRaw());
    }

    @Test
    void rejectsMilligramsAndImplausibleNumbers() {
        assertTrue(VitalMentionParser.parse("126").cards().isEmpty());
        assertFalse(VitalMentionParser.parse("126").handled());
        VitalMentionParser.Outcome mg = VitalMentionParser.parse("血糖 126 毫克");
        assertTrue(mg.handled());
        assertTrue(mg.cards().isEmpty());
        assertTrue(mg.reply().contains("mmol/L"));
        assertTrue(VitalMentionParser.parse("血糖 40").cards().isEmpty());
        assertTrue(VitalMentionParser.parse("血压 300/100").cards().isEmpty());
        assertTrue(VitalMentionParser.parse("血压 90/100").reply().contains("舒张压"));
    }

    @Test
    void pastPeriodDoesNotCreateACard() {
        VitalMentionParser.Outcome morning = VitalMentionParser.parse("早上血压 130/80");
        assertTrue(morning.handled());
        assertTrue(morning.cards().isEmpty());
        assertTrue(morning.reply().contains("指标页"));
        assertTrue(VitalMentionParser.parse("昨天的体重 70").cards().isEmpty());
        assertFalse(VitalMentionParser.parse("昨天血压怎么样").handled());
    }

    @Test
    void twoFactsBecomeTwoCardsAndHalfPressureAsks() {
        VitalMentionParser.Outcome both = VitalMentionParser.parse("血压 165/98，体重 70 公斤");
        assertEquals(2, both.cards().size());
        assertEquals("BP", both.cards().get(0).getMetric());
        assertEquals("WEIGHT", both.cards().get(1).getMetric());

        VitalMentionParser.Outcome half = VitalMentionParser.parse("血压 165");
        assertTrue(half.cards().isEmpty());
        assertTrue(half.reply().contains("舒张压"));

        VitalMentionParser.Outcome mixed = VitalMentionParser.parse("血压 165，体重 70");
        assertEquals(1, mixed.cards().size());
        assertEquals("WEIGHT", mixed.cards().get(0).getMetric());
    }

    @Test
    void promptWithoutNumberAndQuestionStaySeparate() {
        assertTrue(VitalMentionParser.parse("记一下血压").reply().contains("165/98"));
        assertFalse(VitalMentionParser.parse("最近血压怎么样").handled());
        assertTrue(VitalMentionParser.parse("刚才血糖 6.1").handled());
        assertEquals(1, VitalMentionParser.parse("刚才血糖 6.1").cards().size());
    }
}
