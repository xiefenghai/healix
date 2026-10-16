package com.healix.core.medication.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.medication.domain.PeopleMedication;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MedicationSlotRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private static PeopleMedication med(String frequency, String timing) {
        PeopleMedication m = new PeopleMedication();
        m.setFrequency(frequency);
        m.setTimingNote(timing);
        m.setStatus("ACTIVE");
        m.setStartDate(TODAY.minusDays(10));
        return m;
    }

    @Test
    void onceDailyInfersSlotFromFrequencyAndTiming() {
        assertEquals(List.of("MORNING"), MedicationSlotRules.slotsOf(med("QD", "晨起")));
        assertEquals(List.of("MORNING"), MedicationSlotRules.slotsOf(med("QD", null)));
        assertEquals(List.of("BEDTIME"), MedicationSlotRules.slotsOf(med("QN", null)));
        assertEquals(List.of("BEDTIME"), MedicationSlotRules.slotsOf(med("QD", "睡前")));
        assertEquals(List.of("EVENING"), MedicationSlotRules.slotsOf(med("QD", "晚餐后")));
        assertEquals(List.of("NOON"), MedicationSlotRules.slotsOf(med("QD", "午饭后")));
    }

    @Test
    void multiDoseSlots() {
        assertEquals(List.of("MORNING", "EVENING"), MedicationSlotRules.slotsOf(med("BID", null)));
        assertEquals(List.of("MORNING", "NOON", "EVENING"), MedicationSlotRules.slotsOf(med("TID", null)));
        assertEquals(MedicationSlotRules.TIMED_SLOTS, MedicationSlotRules.slotsOf(med("QID", null)));
    }

    @Test
    void prnHasNoDueDoses() {
        PeopleMedication prn = med("PRN", "痛时服");
        assertTrue(MedicationSlotRules.isPrn(prn));
        assertEquals(List.of("PRN"), MedicationSlotRules.slotsOf(prn));
        assertEquals(0, MedicationSlotRules.dueCount(prn));
    }

    @Test
    void explicitSlotsWinAndAreNormalized() {
        PeopleMedication m = med("QD", "晨起");
        m.setSlotsJson("[\"bedtime\",\"MORNING\",\"BEDTIME\",\"OTHER\"]");
        assertEquals(List.of("MORNING", "BEDTIME"), MedicationSlotRules.slotsOf(m));
        m.setSlotsJson("not json");
        assertEquals(List.of("MORNING"), MedicationSlotRules.slotsOf(m));
        assertNull(MedicationSlotRules.toJson(List.of("OTHER")));
    }

    @Test
    void legacyOtherMapsToSingleSlotOnly() {
        assertEquals("BEDTIME", MedicationSlotRules.effectiveSlot("OTHER", List.of("BEDTIME")));
        assertEquals("OTHER", MedicationSlotRules.effectiveSlot("OTHER", List.of("MORNING", "EVENING")));
        assertEquals("OTHER", MedicationSlotRules.effectiveSlot("OTHER", List.of("PRN")));
    }

    @Test
    void stoppedMedIsNotDueFromToday() {
        PeopleMedication m = med("QD", null);
        m.setStatus("STOPPED");
        m.setStopDate(TODAY);
        assertTrue(MedicationSlotRules.isActiveOnDay(m, TODAY.minusDays(1), TODAY));
        assertFalse(MedicationSlotRules.isActiveOnDay(m, TODAY, TODAY));
    }

    @Test
    void courseEndsAfterCourseDays() {
        PeopleMedication m = med("QD", null);
        m.setStartDate(TODAY.minusDays(6));
        m.setCourseDays(7);
        assertTrue(MedicationSlotRules.isActiveOnDay(m, TODAY, TODAY));
        assertFalse(MedicationSlotRules.isActiveOnDay(m, TODAY.plusDays(1), TODAY.plusDays(1)));
        assertFalse(MedicationSlotRules.isActiveOnDay(m, TODAY.minusDays(7), TODAY));
    }

    @Test
    void everyOtherDayAndWeeklyCountFromStartDate() {
        PeopleMedication qod = med("QOD", null);
        qod.setStartDate(TODAY.minusDays(4));
        assertTrue(MedicationSlotRules.isDueOnDay(qod, TODAY, TODAY));
        assertFalse(MedicationSlotRules.isDueOnDay(qod, TODAY.minusDays(1), TODAY));

        PeopleMedication weekly = med("QW", null);
        weekly.setStartDate(TODAY.minusDays(7));
        assertTrue(MedicationSlotRules.isDueOnDay(weekly, TODAY, TODAY));
        assertFalse(MedicationSlotRules.isDueOnDay(weekly, TODAY.minusDays(3), TODAY));

        assertTrue(MedicationSlotRules.isDueOnDay(med("BID", null), TODAY, TODAY));
    }
}
