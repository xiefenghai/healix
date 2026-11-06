package com.healix.agent.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatExtractIntakeNormalizerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<IntakeExtractField> FIELDS = List.of(
            new IntakeExtractField(
                    "basic.psych.stressLevel",
                    "最近压力大吗？",
                    "ENUM",
                    null,
                    null,
                    null,
                    List.of(
                            new ChatExtractOption("LOW", "不大"),
                            new ChatExtractOption("MEDIUM", "有一些"),
                            new ChatExtractOption("HIGH", "长期压力很大"))),
            new IntakeExtractField("basic.sleep.hours", "每天大概睡几个小时？", "NUMBER", "小时", 0d, 16d, List.of()));

    @Test
    void keepsModelFieldsWhenQuoteIsInTheSource() throws Exception {
        String source = "客户说他平时压力很大，一天睡 6 个小时";
        String raw = """
                {"items":[
                  {"field":"basic.psych.stressLevel","value":"HIGH","quote":"平时压力很大"},
                  {"field":"basic.sleep.hours","value":6,"quote":"一天睡 6 个小时"}
                ]}
                """;
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(source, JSON.readTree(raw), FIELDS);
        assertEquals(2, card.getItems().size());
        assertEquals("HIGH", card.getItems().get(0).getArchiveValue());
        assertEquals("INTAKE", card.getItems().get(0).getTarget());
        assertEquals("6", card.getItems().get(1).getArchiveValue());
        assertTrue(card.isIntake());
    }

    @Test
    void acceptsOptionLabelAsTheModelValue() throws Exception {
        String source = "他说长期压力很大";
        String raw = """
                {"items":[{"field":"basic.psych.stressLevel","value":"长期压力很大","quote":"长期压力很大"}]}
                """;
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(source, JSON.readTree(raw), FIELDS);
        assertEquals("HIGH", card.getItems().get(0).getArchiveValue());
    }

    @Test
    void doesNotInventAnswersWhenTheModelReturnsNothing() throws Exception {
        String source = "客户说他平时压力很大，一天睡 6 个小时";
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(source, JSON.readTree("{\"items\":[]}"), FIELDS);
        assertTrue(card.getItems().isEmpty());
    }

    @Test
    void dropsQuoteThatIsNotInTheSource() throws Exception {
        String source = "今天来复诊";
        String raw = """
                {"items":[{"field":"basic.sleep.hours","value":6,"quote":"一天睡 6 个小时"}]}
                """;
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(source, JSON.readTree(raw), FIELDS);
        assertTrue(card.getItems().isEmpty());
    }

    @Test
    void keepsTheFirstValueWhenTheSameFieldIsMentionedTwice() throws Exception {
        String source = "先说一天睡 6 个小时，后来又说一天睡 8 个小时";
        String raw = """
                {"items":[
                  {"field":"basic.sleep.hours","value":6,"quote":"一天睡 6 个小时"},
                  {"field":"basic.sleep.hours","value":8,"quote":"一天睡 8 个小时"}
                ]}
                """;
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(source, JSON.readTree(raw), FIELDS);
        assertEquals(1, card.getItems().size());
        assertEquals("6", card.getItems().get(0).getArchiveValue());
        assertEquals(1, card.getIgnored().size());
        assertEquals("一天睡 8 个小时", card.getIgnored().get(0).getQuote());
        assertTrue(card.getIgnored().get(0).getReason().contains("另一种说法"));
    }

    @Test
    void dropsUnknownField() throws Exception {
        String source = "平时压力很大";
        String raw = """
                {"items":[{"field":"psych.stressLevel","value":"HIGH","quote":"平时压力很大"}]}
                """;
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(source, JSON.readTree(raw), FIELDS);
        assertTrue(card.getItems().isEmpty());
    }
}
