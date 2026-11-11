package com.healix.core.assessment.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AssessmentInputFreshnessTest {

    @Test
    void within_rejectsMissingTimestampAndStaleValues() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 11, 16, 0);
        assertFalse(AssessmentContextBuilder.within(null, now, 365));
        assertFalse(AssessmentContextBuilder.within(now.minusDays(366), now, 365));
        assertTrue(AssessmentContextBuilder.within(now.minusDays(365), now, 365));
        assertTrue(AssessmentContextBuilder.within(now.minusDays(90), now, 90));
        assertFalse(AssessmentContextBuilder.within(now.minusDays(91), now, 90));
    }
}
