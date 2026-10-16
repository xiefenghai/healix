package com.healix.core.medication.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MedicationReminderScanServiceTest {

    private static final LocalDateTime SLOT = LocalDateTime.of(2026, 10, 14, 8, 0);

    private static int seq(int followUpMin, int minutesAfterSlot) {
        return MedicationReminderScanService.dueSeq(SLOT, followUpMin, SLOT.plusMinutes(minutesAfterSlot));
    }

    @Test
    void remindsAtSlotTimeThenFollowsUp() {
        assertEquals(0, seq(30, -1));
        assertEquals(1, seq(30, 0));
        assertEquals(1, seq(30, 29));
        assertEquals(2, seq(30, 30));
        assertEquals(2, seq(30, 149));
        assertEquals(0, seq(30, 150));
    }

    @Test
    void followUpOffOnlyRemindsOnce() {
        assertEquals(1, seq(0, 0));
        assertEquals(1, seq(0, 60));
        assertEquals(1, seq(0, 119));
        assertEquals(0, seq(0, 120));
    }

    @Test
    void noCatchUpAfterTwoHours() {
        assertEquals(0, seq(15, 15 + 120));
        assertEquals(2, seq(60, 61));
    }
}
