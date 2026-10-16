package com.healix.core.assessment.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentContext.FactValue;
import com.healix.core.assessment.support.AssessmentResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RiskEnginesTest {

    private final CdrsAssessmentEngine cdrs = new CdrsAssessmentEngine();
    private final HypertensionAssessmentEngine htn = new HypertensionAssessmentEngine();
    private final ObesityRiskAssessmentEngine obesity = new ObesityRiskAssessmentEngine();

    static AssessmentContext.AssessmentContextBuilder healthyAdult() {
        return AssessmentContext.builder()
                .tenantId("t")
                .peopleId("p")
                .asOf(LocalDateTime.now())
                .gender("FEMALE")
                .ageYears(30)
                .heightCm(new BigDecimal("160"))
                .weightKg(new BigDecimal("52"))
                .bmi(new BigDecimal("20.3"))
                .waistCm(new BigDecimal("68"))
                .sbp(new BigDecimal("108"))
                .dbp(new BigDecimal("70"))
                .bpReadings(3)
                .familyHistoryCollected(true)
                .fhDiabetes(false)
                .fhHypertension(false)
                .fhAscvd(false)
                .fhEarlyAscvd(false)
                .presentIllnessCollected(true)
                .pastHistoryCollected(true)
                .prediabetesInArchive(false)
                .exerciseFrequency("DAILY")
                .smokingStatus("NEVER")
                .drinkingStatus("NEVER")
                .dietCollected(true)
                .dietType("LOW_SALT");
    }

    // ------------------------------------------------------------------ CDRS

    @Test
    void cdrsHighByScore() {
        AssessmentContext ctx = healthyAdult()
                .gender("MALE")
                .ageYears(66)
                .bmi(new BigDecimal("31.0"))
                .waistCm(new BigDecimal("96"))
                .sbp(new BigDecimal("165"))
                .fhDiabetes(true)
                .build();
        AssessmentResult r = cdrs.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertEquals("HIGH", r.getLevel());
        assertEquals(AssessmentTier.HIGH, r.getTier());
        assertTrue(r.getScore().intValue() >= 25);
    }

    @Test
    void cdrsHighByFactorEvenWhenScoreLow() {
        AssessmentContext ctx = healthyAdult().ageYears(42).build();
        AssessmentResult r = cdrs.evaluate(ctx);
        assertTrue(r.getScore().intValue() < 25);
        assertEquals("HIGH", r.getLevel());
        assertTrue(r.getFactorGroups().get(0).hits().stream().anyMatch(h -> "AGE_GE_40".equals(h.code())));
    }

    @Test
    void cdrsLowWhenYoungAndHealthy() {
        AssessmentResult r = cdrs.evaluate(healthyAdult().build());
        assertEquals("LOW", r.getLevel());
        assertNull(r.getSuspicion());
    }

    @Test
    void cdrsQuestionnaireFactCountsAsHighRisk() {
        AssessmentContext ctx = healthyAdult()
                .facts(Map.of(InputField.GDM_HISTORY, new FactValue("YES", "QUESTIONNAIRE_PATIENT", LocalDateTime.now())))
                .build();
        assertEquals("HIGH", cdrs.evaluate(ctx).getLevel());
    }

    @Test
    void cdrsIncompleteWithoutFamilyHistory() {
        AssessmentResult r = cdrs.evaluate(healthyAdult().familyHistoryCollected(false).fhDiabetes(null).build());
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
        assertTrue(r.getMissing().stream().anyMatch(m -> "FAMILY_HISTORY".equals(m.field())));
    }

    @Test
    void cdrsSuspicionOnDiagnosticGlucose() {
        AssessmentResult r = cdrs.evaluate(healthyAdult().fastingGlucose(new BigDecimal("7.4")).build());
        assertNotNull(r.getSuspicion());
        assertEquals("diabetes", r.getSuspicion().diseaseCode());
    }

    @Test
    void cdrsNotApplicableWhenDiagnosedOrOutOfAge() {
        assertFalse(cdrs.applicable(healthyAdult().hasDiabetesArchive(true).build()));
        assertFalse(cdrs.applicable(healthyAdult().ageYears(80).build()));
        assertTrue(cdrs.applicable(healthyAdult().build()));
    }

    // ------------------------------------------------------------------ 高血压风险

    @Test
    void htnLowWhenNormal() {
        AssessmentResult r = htn.evaluate(healthyAdult().build());
        assertEquals("LOW", r.getLevel());
        assertNull(r.getSuspicion());
    }

    @Test
    void htnMidWhenHighNormal() {
        AssessmentResult r = htn.evaluate(healthyAdult().sbp(new BigDecimal("132")).dbp(new BigDecimal("82")).build());
        assertEquals("MID", r.getLevel());
    }

    @Test
    void htnHighAndSuspectedWhenGrade1() {
        AssessmentResult r = htn.evaluate(healthyAdult().sbp(new BigDecimal("150")).dbp(new BigDecimal("92")).build());
        assertEquals("HIGH", r.getLevel());
        assertNotNull(r.getSuspicion());
        assertEquals("hypertension", r.getSuspicion().diseaseCode());
    }

    @Test
    void htnIncompleteWithoutBp() {
        AssessmentResult r = htn.evaluate(healthyAdult().sbp(null).dbp(null).build());
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
    }

    @Test
    void htnTherapyTriggersSuspicion() {
        AssessmentResult r = htn.evaluate(healthyAdult().activeDrugCategories(Set.of("BP")).build());
        assertNotNull(r.getSuspicion());
    }

    // ------------------------------------------------------------------ 肥胖筛查

    @Test
    void obesityLevels() {
        assertEquals("NORMAL", obesity.evaluate(healthyAdult().build()).getLevel());
        assertEquals("NORMAL_CENTRAL",
                obesity.evaluate(healthyAdult().waistCm(new BigDecimal("88")).build()).getLevel());
        assertEquals("OVERWEIGHT", obesity.evaluate(healthyAdult().bmi(new BigDecimal("25.5")).build()).getLevel());
        AssessmentResult ob = obesity.evaluate(healthyAdult().bmi(new BigDecimal("29.1")).build());
        assertEquals("OBESITY", ob.getLevel());
        assertEquals(AssessmentTier.HIGH, ob.getTier());
        assertNotNull(ob.getSuspicion());
    }

    @Test
    void obesityIncompleteWithoutWeight() {
        AssessmentResult r = obesity.evaluate(healthyAdult().weightKg(null).bmi(null).build());
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
    }
}
