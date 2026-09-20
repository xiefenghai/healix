package com.healix.core.assessment.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.assessment.support.HypertensionRiskRules;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HypertensionAssessmentEngineTest {

    private final HypertensionAssessmentEngine engine = new HypertensionAssessmentEngine();

    @Test
    void bpGradeTakesHigherOfSbpAndDbp() {
        assertEquals(
                HypertensionRiskRules.GRADE_NORMAL,
                HypertensionRiskRules.classifyBpGrade(new BigDecimal("118"), new BigDecimal("76")));
        assertEquals(
                HypertensionRiskRules.GRADE_PRE,
                HypertensionRiskRules.classifyBpGrade(new BigDecimal("128"), new BigDecimal("76")));
        assertEquals(
                HypertensionRiskRules.GRADE_1,
                HypertensionRiskRules.classifyBpGrade(new BigDecimal("150"), new BigDecimal("85")));
        assertEquals(
                HypertensionRiskRules.GRADE_2,
                HypertensionRiskRules.classifyBpGrade(new BigDecimal("150"), new BigDecimal("105")));
        assertEquals(
                HypertensionRiskRules.GRADE_3,
                HypertensionRiskRules.classifyBpGrade(new BigDecimal("182"), new BigDecimal("90")));
    }

    @Test
    void grade3IsCvHighRegardlessOfFactors() {
        assertTrue(HypertensionRiskRules.isCvHighRisk(HypertensionRiskRules.GRADE_3, 0));
        assertTrue(HypertensionRiskRules.isCvHighRisk(HypertensionRiskRules.GRADE_2, 1));
        assertFalse(HypertensionRiskRules.isCvHighRisk(HypertensionRiskRules.GRADE_2, 0));
        assertTrue(HypertensionRiskRules.isCvHighRisk(HypertensionRiskRules.GRADE_1, 3));
        assertFalse(HypertensionRiskRules.isCvHighRisk(HypertensionRiskRules.GRADE_1, 2));
        assertTrue(HypertensionRiskRules.isCvHighRisk(HypertensionRiskRules.GRADE_PRE, 3));
    }

    @Test
    void ageAloneMakesSusceptible() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(46)
                .gender("MALE")
                .sbp(new BigDecimal("118"))
                .dbp(new BigDecimal("76"))
                .bmi(new BigDecimal("22.0"))
                .waistCm(new BigDecimal("80"))
                .familyHistoryCollected(true)
                .firstDegreeHypertensionFamilyHistory(false)
                .smokingCollected(true)
                .smokingStatus("NEVER")
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .drinkingCollected(true)
                .drinkingStatus("NEVER")
                .dietCollected(true)
                .dietType("LOW_SALT")
                .build();
        Map<String, Object> out =
                HypertensionRiskRules.evaluateSusceptible(ctx, HypertensionRiskRules.GRADE_NORMAL);
        assertTrue(Boolean.TRUE.equals(out.get("susceptible")));
    }

    @Test
    void evaluateOutputsThreeDimensions() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(58)
                .gender("MALE")
                .sbp(new BigDecimal("168"))
                .dbp(new BigDecimal("98"))
                .bmi(new BigDecimal("29.0"))
                .waistCm(new BigDecimal("96"))
                .heartRate(new BigDecimal("86"))
                .familyHistoryCollected(true)
                .firstDegreeHypertensionFamilyHistory(true)
                .earlyAscvdFamilyHistory(false)
                .firstDegreeAscvdFamilyHistory(false)
                .smokingCollected(true)
                .smokingStatus("CURRENT")
                .exerciseCollected(true)
                .exerciseFrequency("NONE")
                .drinkingCollected(true)
                .drinkingStatus("NEVER")
                .dietCollected(true)
                .dietType("NORMAL")
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .tc(new BigDecimal("6.0"))
                .tcUnit("mmol/L")
                .hdlC(new BigDecimal("1.2"))
                .hdlUnit("mmol/L")
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertEquals(HypertensionRiskRules.GRADE_2, r.getLevel());
        assertTrue(Boolean.TRUE.equals(r.getExtras().get("cvRiskHigh")));
        assertTrue(Boolean.TRUE.equals(r.getExtras().get("susceptible")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) r.getExtras().get("dimensions");
        assertEquals(3, dims.size());
    }

    @Test
    void notApplicableWhenKnownHypertension() {
        AssessmentContext withArchive = AssessmentContext.builder()
                .ageYears(50)
                .gender("FEMALE")
                .sbp(new BigDecimal("130"))
                .dbp(new BigDecimal("82"))
                .hasHypertensionDiseaseArchive(true)
                .build();
        assertFalse(engine.applicable(withArchive));

        AssessmentContext withPresent = AssessmentContext.builder()
                .ageYears(50)
                .gender("FEMALE")
                .sbp(new BigDecimal("130"))
                .dbp(new BigDecimal("82"))
                .hasHypertensionPresentIllness(true)
                .build();
        assertFalse(engine.applicable(withPresent));
    }

    @Test
    void incompleteWithoutBp() {
        AssessmentResult r = engine.evaluate(AssessmentContext.builder().ageYears(40).build());
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
        assertTrue(r.getMissingFields().contains("BLOOD_PRESSURE_SYS"));
    }
}
