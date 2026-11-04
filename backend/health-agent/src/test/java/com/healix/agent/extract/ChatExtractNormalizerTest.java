package com.healix.agent.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.common.util.JsonUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatExtractNormalizerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    @Test
    void bloodPressureUsesExplicitClockAndHome() {
        ChatExtractCard card = ChatExtractNormalizer.normalize(
                "今天7点半血压138/86",
                payload(item("VITAL", "BLOOD_PRESSURE", null, "今天7点半血压138/86", null), null),
                TODAY);
        assertEquals(1, card.getItems().size());
        ChatExtractRow row = card.getItems().get(0);
        assertEquals("READY", row.getStatus());
        assertEquals("2026-10-23T07:30", row.getMeasuredAt());
        assertEquals("HOME", row.getBpContext());
        assertEquals(new BigDecimal("138"), row.getSystolic());
        assertEquals(new BigDecimal("86"), row.getDiastolic());
    }

    @Test
    void modelProfileFieldDoesNotDropGlucose() {
        String json = """
                {"items":[{"code":"BLOOD_GLUCOSE","value":5.8,"unit":"mmol/L","mealContext":"FASTING","quote":"今天早上空腹血糖 5.8。"}],"profile":[],"ignored":[]}
                """;
        ChatExtractCard card = ChatExtractNormalizer.normalize(
                "客户：今天早上空腹血糖 5.8。",
                ChatExtractLlmPayload.read(json),
                TODAY);
        assertEquals(1, card.getItems().size());
        ChatExtractRow row = card.getItems().get(0);
        assertEquals("BLOOD_GLUCOSE", row.getCode());
        assertEquals(new BigDecimal("5.8"), row.getValue());
        assertEquals("FASTING", row.getMealContext());
        assertEquals("2026-10-23", row.getMeasuredAt());
        assertEquals("MISSING_TIME", row.getStatus());
    }

    @Test
    void morningGlucoseKeepsDateButNotClock() {
        ChatExtractLlmPayload.Item glucose = item("VITAL", "BLOOD_GLUCOSE", "6.2", "今天早上空腹血糖6.2", null);
        ChatExtractCard card = ChatExtractNormalizer.normalize("今天早上空腹血糖6.2", payload(glucose, null), TODAY);
        ChatExtractRow row = card.getItems().get(0);
        assertEquals("2026-10-23", row.getMeasuredAt());
        assertEquals("MISSING_TIME", row.getStatus());
        assertEquals("FASTING", row.getMealContext());
        assertFalse("2026-10-23T07:30".equals(row.getMeasuredAt()));
    }

    @Test
    void jinMarkedByModelStillConverts() {
        ChatExtractLlmPayload.Item weight = item("VITAL", "WEIGHT", "120", "体重120斤", null);
        weight.setSourceUnit("斤");
        weight.setUnit("斤");
        ChatExtractRow row = ChatExtractNormalizer.normalize("体重120斤", payload(weight, null), TODAY).getItems().get(0);
        assertEquals(new BigDecimal("60.0"), row.getValue());
        assertEquals("kg", row.getUnit());
        assertEquals(new BigDecimal("120"), row.getSourceValue());
    }

    @Test
    void laterMetricsInheritMorningAndSevenOclock() {
        String source = "客户：今天早上7点半在家量了血压，138/86。7点空腹血糖6.2。体重120斤，腰围82厘米。心率72。";
        ChatExtractLlmPayload payload = new ChatExtractLlmPayload();
        ChatExtractLlmPayload.Item pressure = item("VITAL", "BLOOD_PRESSURE", null, "138/86", null);
        pressure.setSystolic(new BigDecimal("138"));
        pressure.setDiastolic(new BigDecimal("86"));
        ChatExtractLlmPayload.Item weight = item("VITAL", "WEIGHT", "120", "体重120斤", null);
        weight.setSourceUnit("斤");
        payload.setItems(List.of(
                pressure,
                item("VITAL", "BLOOD_GLUCOSE", "6.2", "血糖6.2", null),
                weight,
                item("VITAL", "WAIST", "82", "腰围82厘米", null),
                item("VITAL", "HEART_RATE", "72", "心率72", null)));
        ChatExtractCard card = ChatExtractNormalizer.normalize(source, payload, TODAY);
        assertEquals("2026-10-23T07:30", card.getItems().get(0).getMeasuredAt());
        ChatExtractRow glucose = card.getItems().get(1);
        assertEquals("2026-10-23T07:00", glucose.getMeasuredAt());
        assertEquals("FASTING", glucose.getMealContext());
        assertEquals("READY", glucose.getStatus());
        ChatExtractRow savedWeight = card.getItems().get(2);
        assertEquals(new BigDecimal("60.0"), savedWeight.getValue());
        assertEquals("kg", savedWeight.getUnit());
        assertEquals("2026-10-23T07:00", savedWeight.getMeasuredAt());
        assertEquals("2026-10-23T07:00", card.getItems().get(3).getMeasuredAt());
        assertEquals("2026-10-23T07:00", card.getItems().get(4).getMeasuredAt());
    }

    @Test
    void heartRateSplitAcrossLinesStillInheritsSevenOclock() {
        String source = "客户：今天早上7点半在家量了血压，138/86。7点空腹血糖6.2。体重120斤，腰围82厘米。心率\n72。";
        ChatExtractLlmPayload.Item heart = item("VITAL", "HEART_RATE", "72", "心率 72。", null);
        ChatExtractRow row = ChatExtractNormalizer.normalize(source, payload(heart, null), TODAY).getItems().get(0);
        assertEquals("2026-10-23T07:00", row.getMeasuredAt());
        assertEquals("READY", row.getStatus());
    }

    @Test
    void vaguePeriodDoesNotInheritEarlierDate() {
        String source = "客户：今天早上七八点心率 80。前天腰围 85 厘米。上周血糖 6.1。";
        ChatExtractLlmPayload payload = new ChatExtractLlmPayload();
        payload.setItems(List.of(
                item("VITAL", "HEART_RATE", "80", "今天早上七八点心率 80", null),
                item("VITAL", "WAIST", "85", "前天腰围 85 厘米", null),
                item("VITAL", "BLOOD_GLUCOSE", "6.1", "上周血糖 6.1", null)));
        ChatExtractCard card = ChatExtractNormalizer.normalize(source, payload, TODAY);
        ChatExtractRow heart = card.getItems().get(0);
        assertEquals("2026-10-23", heart.getMeasuredAt());
        assertEquals("MISSING_TIME", heart.getStatus());
        ChatExtractRow waist = card.getItems().get(1);
        assertEquals("2026-10-21", waist.getMeasuredAt());
        assertEquals("MISSING_TIME", waist.getStatus());
        ChatExtractRow glucose = card.getItems().get(2);
        assertEquals(null, glucose.getMeasuredAt());
        assertEquals("MISSING_DATE", glucose.getStatus());
    }

    @Test
    void recentAndFewDaysAlsoClearInheritedDate() {
        String source = "前天7点血糖5.4。最近血糖5.5。前几天心率70。";
        ChatExtractLlmPayload payload = new ChatExtractLlmPayload();
        payload.setItems(List.of(
                item("VITAL", "BLOOD_GLUCOSE", "5.4", "前天7点血糖5.4", null),
                item("VITAL", "BLOOD_GLUCOSE", "5.5", "最近血糖5.5", null),
                item("VITAL", "HEART_RATE", "70", "前几天心率70", null)));
        ChatExtractCard card = ChatExtractNormalizer.normalize(source, payload, TODAY);
        assertEquals("2026-10-21T07:00", card.getItems().get(0).getMeasuredAt());
        assertEquals(null, card.getItems().get(1).getMeasuredAt());
        assertEquals("MISSING_DATE", card.getItems().get(1).getStatus());
        assertEquals(null, card.getItems().get(2).getMeasuredAt());
        assertEquals("MISSING_DATE", card.getItems().get(2).getStatus());
    }

    @Test
    void clockDoesNotCrossIntoAnotherDay() {
        String source = "昨天7点血糖5.6。今天体重70公斤";
        ChatExtractLlmPayload.Item weight = item("VITAL", "WEIGHT", "70", "体重70公斤", null);
        ChatExtractRow row = ChatExtractNormalizer.normalize(source, payload(weight, null), TODAY).getItems().get(0);
        assertEquals("2026-10-23", row.getMeasuredAt());
        assertEquals("MISSING_TIME", row.getStatus());
    }

    @Test
    void jinConvertsToKilograms() {
        ChatExtractLlmPayload.Item weight = item("VITAL", "WEIGHT", "120", "体重120斤", null);
        ChatExtractRow row = ChatExtractNormalizer.normalize("体重120斤", payload(weight, null), TODAY).getItems().get(0);
        assertEquals(new BigDecimal("60.0"), row.getValue());
        assertEquals("斤", row.getSourceUnit());
        assertEquals("kg", row.getUnit());
    }

    @Test
    void weightWithoutUnitCannotSave() {
        ChatExtractLlmPayload.Item weight = item("VITAL", "WEIGHT", "70", "今天7点体重70", null);
        ChatExtractRow row = ChatExtractNormalizer.normalize("今天7点体重70", payload(weight, null), TODAY).getItems().get(0);
        assertEquals("MISSING_UNIT", row.getStatus());
    }

    @Test
    void reversedBloodPressureIsNotSwapped() {
        ChatExtractLlmPayload.Item bp = item("VITAL", "BLOOD_PRESSURE", null, "今天7点血压86/138", null);
        bp.setSystolic(new BigDecimal("86"));
        bp.setDiastolic(new BigDecimal("138"));
        ChatExtractRow row = ChatExtractNormalizer.normalize("今天7点血压86/138", payload(bp, null), TODAY).getItems().get(0);
        assertEquals(new BigDecimal("86"), row.getSystolic());
        assertEquals(new BigDecimal("138"), row.getDiastolic());
        assertEquals("BP_ORDER", row.getStatus());
    }

    @Test
    void oneOffSmokingIsIgnored() {
        ChatExtractLlmPayload.Item smoking = item("ARCHIVE", "lifestyle.smoking.cigarettesPerDay", "2", "今天抽了两根", true);
        ChatExtractCard card = ChatExtractNormalizer.normalize("今天抽了两根", payload(smoking, null), TODAY);
        assertTrue(card.getItems().isEmpty());
        assertEquals("NOT_HABIT", card.getIgnored().get(0).getReason());
    }

    @Test
    void quitYearsAreDerived() {
        ChatExtractLlmPayload.Item year = item("ARCHIVE", "lifestyle.smoking.quitYear", "2020", "烟已经戒了两年", true);
        ChatExtractRow row =
                ChatExtractNormalizer.normalize("烟已经戒了两年", payload(year, null), TODAY).getItems().get(0);
        assertEquals("2024", row.getArchiveValue());
        assertTrue(row.isDerived());
    }

    @Test
    void lowSaltAndLowSugarBecomeDietType() {
        String source = "平时食欲正常，吃饭还算规律，一直是低盐低糖。口味清淡，不太吃辣。";
        ChatExtractLlmPayload.Item preference = item(
                "ARCHIVE",
                "diet.preference",
                "低盐低糖，口味清淡，不太吃辣",
                "一直是低盐低糖。口味清淡，不太吃辣",
                true);
        ChatExtractCard card = ChatExtractNormalizer.normalize(source, payload(preference, null), TODAY);
        ChatExtractRow type = card.getItems().stream()
                .filter(row -> "diet.type".equals(row.getCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("LOW_SALT_SUGAR", type.getArchiveValue());
        ChatExtractRow kept = card.getItems().stream()
                .filter(row -> "diet.preference".equals(row.getCode()))
                .findFirst()
                .orElseThrow();
        assertEquals("口味清淡，不太吃辣", kept.getArchiveValue());

        ChatExtractCard saltOnly = ChatExtractNormalizer.normalize(
                "平时吃低盐。",
                payload(item("ARCHIVE", "diet.preference", "低盐", "平时吃低盐", true), null),
                TODAY);
        assertEquals(
                "LOW_SALT",
                saltOnly.getItems().stream()
                        .filter(row -> "diet.type".equals(row.getCode()))
                        .findFirst()
                        .orElseThrow()
                        .getArchiveValue());
        assertTrue(saltOnly.getItems().stream().noneMatch(row -> "diet.preference".equals(row.getCode())));
    }

    @Test
    void spokenHabitsFillOptionFields() {
        String source = "平时食欲正常，吃饭还算规律。每周快走三次，每次30分钟，强度中等。晚上一般睡6小时，睡眠一般，有点打鼾。烟已经戒了。酒偶尔喝，一周两次，啤酒，一次一瓶。";
        ChatExtractLlmPayload payload = new ChatExtractLlmPayload();
        payload.setItems(List.of(
                item("ARCHIVE", "diet.preference", "食欲正常，吃饭还算规律", "平时食欲正常，吃饭还算规律", true),
                item("ARCHIVE", "exercise.type", "每周快走三次，每次30分钟，中等", "每周快走三次，每次30分钟，强度中等", true),
                item("ARCHIVE", "lifestyle.drinking.type", "偶尔喝，一周两次，啤酒", "酒偶尔喝，一周两次，啤酒", true)));
        ChatExtractCard card = ChatExtractNormalizer.normalize(source, payload, TODAY);
        assertEquals("NORMAL", archiveValue(card, "diet.appetite"));
        assertEquals("FAIR", archiveValue(card, "diet.habit"));
        assertTrue(card.getItems().stream().noneMatch(row -> "diet.preference".equals(row.getCode())));
        assertEquals("WEEKLY_3", archiveValue(card, "exercise.frequency"));
        assertEquals("30", archiveValue(card, "exercise.durationMin"));
        assertEquals("MODERATE", archiveValue(card, "exercise.intensity"));
        assertEquals("快走", archiveValue(card, "exercise.type"));
        assertEquals("6", archiveValue(card, "sleep.hours"));
        assertEquals("FAIR", archiveValue(card, "sleep.quality"));
        assertEquals("SNORING", archiveValue(card, "sleep.disorder"));
        assertEquals("FORMER", archiveValue(card, "lifestyle.smoking.status"));
        assertEquals("OCCASIONAL", archiveValue(card, "lifestyle.drinking.status"));
        assertEquals("WEEKLY_1_2", archiveValue(card, "lifestyle.drinking.frequency"));
        assertEquals("啤酒", archiveValue(card, "lifestyle.drinking.type"));
        assertEquals("一瓶", archiveValue(card, "lifestyle.drinking.amountPerDay"));

        ChatExtractCard never = ChatExtractNormalizer.normalize(
                "平时不抽烟，也不喝酒。",
                new ChatExtractLlmPayload(),
                TODAY);
        assertEquals("NEVER", archiveValue(never, "lifestyle.smoking.status"));
        assertEquals("NEVER", archiveValue(never, "lifestyle.drinking.status"));
        assertTrue(never.getItems().stream().noneMatch(row -> "lifestyle.smoking.cigarettesPerDay".equals(row.getCode())));
    }

    private static String archiveValue(ChatExtractCard card, String code) {
        return card.getItems().stream()
                .filter(row -> code.equals(row.getCode()))
                .findFirst()
                .orElseThrow()
                .getArchiveValue();
    }

    @Test
    void fullPassageKeepsEveryWhitelistedRow() {
        List<ChatExtractLlmPayload.Item> items = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            items.add(item("VITAL", "HEART_RATE", "70", "心率70", null));
        }
        ChatExtractLlmPayload payload = new ChatExtractLlmPayload();
        payload.setItems(items);
        ChatExtractCard card = ChatExtractNormalizer.normalize("心率70", payload, TODAY);
        assertEquals(24, card.getItems().size());
        assertFalse(card.isOverflow());
    }

    @Test
    void labLinesDoNotDropBloodPressure() {
        ChatExtractLlmPayload.Ignored lab = new ChatExtractLlmPayload.Ignored();
        lab.setQuote("糖化血红蛋白 6.8% 参考范围 4.0-6.0");
        lab.setReason("LAB_PANEL");
        ChatExtractLlmPayload raw = payload(item("VITAL", "BLOOD_PRESSURE", null, "今天7点半血压138/86", null), null);
        raw.setIgnored(List.of(lab));
        ChatExtractCard card = ChatExtractNormalizer.normalize("今天7点半血压138/86\n糖化血红蛋白 6.8% 参考范围 4.0-6.0", raw, TODAY);
        assertEquals(1, card.getItems().size());
        assertTrue(card.isLabHint());
    }

    private static ChatExtractLlmPayload payload(ChatExtractLlmPayload.Item item, ChatExtractLlmPayload.Ignored ignored) {
        ChatExtractLlmPayload payload = new ChatExtractLlmPayload();
        if (item != null) {
            payload.setItems(List.of(item));
        }
        if (ignored != null) {
            payload.setIgnored(List.of(ignored));
        }
        return payload;
    }

    private static ChatExtractLlmPayload.Item item(String target, String code, String value, String quote, Boolean habit) {
        ChatExtractLlmPayload.Item item = new ChatExtractLlmPayload.Item();
        item.setTarget(target);
        item.setCode(code);
        if (value != null) {
            item.setValue(JsonUtils.readTree(value.matches("-?\\d+(\\.\\d+)?") ? value : "\"" + value + "\""));
        }
        item.setQuote(quote);
        item.setHabit(habit);
        return item;
    }
}
