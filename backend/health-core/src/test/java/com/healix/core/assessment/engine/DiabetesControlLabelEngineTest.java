package com.healix.core.assessment.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiabetesControlLabelEngineTest {

    private final DiabetesControlLabelEngine engine = new DiabetesControlLabelEngine();

    @Test
    void applicableOnlyWhenKnownDiabetes() {
        assertFalse(engine.applicable(AssessmentContext.builder()
                .tenantId("t")
                .peopleId("p")
                .hasDiabetesDiseaseArchive(false)
                .hasDiabetesPresentIllness(false)
                .build()));
        assertTrue(engine.applicable(AssessmentContext.builder()
                .tenantId("t")
                .peopleId("p")
                .hasDiabetesDiseaseArchive(true)
                .build()));
    }

    @Test
    void evaluateGreenLabel() {
        LocalDateTime now = LocalDateTime.now();
        AssessmentContext ctx = AssessmentContext.builder()
                .tenantId("t")
                .peopleId("p")
                .ageYears(50)
                .hasDiabetesDiseaseArchive(true)
                .hba1c(new BigDecimal("6.5"))
                .hba1cRecordedAt(now.minusDays(10))
                .fastingGlucose(new BigDecimal("6.0"))
                .fastingGlucoseRecordedAt(now.minusDays(3))
                .postprandialGlucose(new BigDecimal("8.0"))
                .postprandialGlucoseRecordedAt(now.minusDays(3))
                .controlLabelAsOf(now)
                .controlLabelWindowStart(now.minusDays(90))
                .hypoEvents14d(0)
                .diabetesComorbidityHits(List.of())
                .diabetesComorbidityCount(0)
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals("GREEN", r.getLevel());
        assertTrue(r.getAdvice().contains("维持"));
    }

    @Test
    void staleMetricsOutsideWindowBecomeNone() {
        LocalDateTime now = LocalDateTime.now();
        AssessmentContext ctx = AssessmentContext.builder()
                .tenantId("t")
                .peopleId("p")
                .ageYears(50)
                .hasDiabetesPresentIllness(true)
                .hba1c(new BigDecimal("6.5"))
                .hba1cRecordedAt(now.minusDays(120))
                .controlLabelAsOf(now)
                .controlLabelWindowStart(now.minusDays(90))
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals("NONE", r.getLevel());
    }
}
