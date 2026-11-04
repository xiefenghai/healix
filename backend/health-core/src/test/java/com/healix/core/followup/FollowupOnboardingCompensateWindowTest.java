package com.healix.core.followup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.followup.service.FollowupScheduleService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FollowupOnboardingCompensateWindowTest {

    private static final LocalDate SCAN = LocalDate.of(2026, 10, 22);

    @Test
    void includesJoinDayAndSixDaysBefore() {
        assertTrue(FollowupScheduleService.withinOnboardingCompensateWindow(SCAN, SCAN));
        assertTrue(FollowupScheduleService.withinOnboardingCompensateWindow(SCAN.minusDays(6), SCAN));
    }

    @Test
    void excludesOlderJoinAndMissingDates() {
        assertFalse(FollowupScheduleService.withinOnboardingCompensateWindow(SCAN.minusDays(7), SCAN));
        assertFalse(FollowupScheduleService.withinOnboardingCompensateWindow(null, SCAN));
        assertFalse(FollowupScheduleService.withinOnboardingCompensateWindow(SCAN, null));
    }
}
