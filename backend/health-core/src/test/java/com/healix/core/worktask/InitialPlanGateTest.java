package com.healix.core.worktask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.worktask.support.InitialPlanGate;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class InitialPlanGateTest {

    private static final LocalDate SCAN = LocalDate.of(2026, 10, 22);

    @Test
    void doneOrCancelledOpensPlanImmediately() {
        assertTrue(InitialPlanGate.ready("DONE", SCAN.plusDays(2), SCAN, SCAN));
        assertTrue(InitialPlanGate.ready("CANCELLED", null, SCAN, SCAN));
        assertEquals("首诊已完成，请制定管理方案", InitialPlanGate.summary("DONE"));
    }

    @Test
    void openOnboardingWaitsUntilDueDayPasses() {
        assertFalse(InitialPlanGate.ready("OPEN", SCAN, SCAN.minusDays(1), SCAN));
        assertFalse(InitialPlanGate.ready("OPEN", SCAN.plusDays(1), SCAN, SCAN));
        assertTrue(InitialPlanGate.ready("OPEN", SCAN.minusDays(1), SCAN.minusDays(4), SCAN));
    }

    @Test
    void missingOnboardingUsesFollowUpSlaFromJoinDay() {
        assertFalse(InitialPlanGate.ready(null, null, SCAN, SCAN));
        assertFalse(InitialPlanGate.ready(null, null, SCAN.minusDays(2), SCAN));
        assertTrue(InitialPlanGate.ready(null, null, SCAN.minusDays(3), SCAN));
        assertEquals("首诊超时未办结，请制定管理方案", InitialPlanGate.summary("OPEN"));
    }
}
