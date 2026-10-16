package com.healix.core.assessment.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeverityEnginesTest {

    private final DiabetesSeverityEngine dm = new DiabetesSeverityEngine();
    private final HypertensionSeverityEngine htn = new HypertensionSeverityEngine();
    private final ObesitySeverityEngine obesity = new ObesitySeverityEngine();

    // ------------------------------------------------------------------ 糖尿病

    @Test
    void diabetesApplicableOnlyWhenDiagnosed() {
        assertFalse(dm.applicable(RiskEnginesTest.healthyAdult().build()));
        assertTrue(dm.applicable(RiskEnginesTest.healthyAdult().hasDiabetesArchive(true).build()));
        assertTrue(dm.applicable(RiskEnginesTest.healthyAdult().presentIllness(Set.of("DIABETES")).build()));
    }

    @Test
    void diabetesGreenIsLowTier() {
        LocalDateTime now = LocalDateTime.now();
        AssessmentContext ctx = RiskEnginesTest.healthyAdult()
                .ageYears(50)
                .hasDiabetesArchive(true)
                .controlWindowStart(now.minusDays(90))
                .hba1c(new BigDecimal("6.5"))
                .hba1cRecordedAt(now.minusDays(10))
                .fastingGlucose(new BigDecimal("6.0"))
                .fastingGlucoseRecordedAt(now.minusDays(3))
                .postprandialGlucose(new BigDecimal("8.0"))
                .postprandialGlucoseRecordedAt(now.minusDays(3))
                .build();
        AssessmentResult r = dm.evaluate(ctx);
        assertEquals("GREEN", r.getLevel());
        assertEquals(AssessmentTier.LOW, r.getTier());
    }

    @Test
    void diabetesStaleDataIsIncomplete() {
        LocalDateTime now = LocalDateTime.now();
        AssessmentContext ctx = RiskEnginesTest.healthyAdult()
                .hasDiabetesArchive(true)
                .controlWindowStart(now.minusDays(90))
                .hba1c(new BigDecimal("6.5"))
                .hba1cRecordedAt(now.minusDays(120))
                .build();
        AssessmentResult r = dm.evaluate(ctx);
        assertEquals("NONE", r.getLevel());
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
        assertNull(r.getTier());
    }

    // ------------------------------------------------------------------ 高血压分层

    private static AssessmentContext.AssessmentContextBuilder htnPatient() {
        return RiskEnginesTest.healthyAdult().hasHypertensionArchive(true);
    }

    @Test
    void htnGrade1NoRiskFactorIsLow() {
        AssessmentResult r = htn.evaluate(htnPatient().archiveHighestSbp(new BigDecimal("150")).archiveHighestDbp(new BigDecimal("92")).build());
        assertEquals("LOW", r.getLevel());
        assertTrue(htn.provisional());
    }

    @Test
    void htnGrade2WithOneRiskFactorTakesHigherCell() {
        AssessmentContext ctx = htnPatient()
                .archiveHighestSbp(new BigDecimal("165"))
                .archiveHighestDbp(new BigDecimal("100"))
                .smokingStatus("CURRENT")
                .build();
        AssessmentResult r = htn.evaluate(ctx);
        assertEquals("HIGH", r.getLevel());
        assertEquals(AssessmentTier.HIGH, r.getTier());
    }

    @Test
    void htnClinicalComplicationIsVeryHigh() {
        AssessmentContext ctx = htnPatient()
                .archiveHighestSbp(new BigDecimal("145"))
                .archiveHighestDbp(new BigDecimal("85"))
                .presentIllness(Set.of("HYPERTENSION", "CHD"))
                .build();
        assertEquals("VERY_HIGH", htn.evaluate(ctx).getLevel());
    }

    @Test
    void htnUsesQuestionnaireHighestBpWhenNoArchiveValue() {
        AssessmentContext ctx = htnPatient()
                .facts(Map.of(
                        InputField.HTN_MAX_SBP, new FactValue(new BigDecimal("185"), "QUESTIONNAIRE_PATIENT", LocalDateTime.now()),
                        InputField.HTN_MAX_DBP, new FactValue(new BigDecimal("100"), "QUESTIONNAIRE_PATIENT", LocalDateTime.now())))
                .build();
        AssessmentResult r = htn.evaluate(ctx);
        assertEquals("HIGH", r.getLevel());
        assertTrue(r.getDimensions().get(0).hint().contains("问卷"));
    }

    @Test
    void htnIncompleteWithoutAnyBp() {
        AssessmentResult r = htn.evaluate(htnPatient().sbp(null).dbp(null).build());
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
    }

    // ------------------------------------------------------------------ 肥胖分层

    private static AssessmentContext.AssessmentContextBuilder obesityPatient(String bmi) {
        return RiskEnginesTest.healthyAdult().hasObesityArchive(true).bmi(new BigDecimal(bmi));
    }

    @Test
    void obesityMildNoComplicationIsLow() {
        AssessmentResult r = obesity.evaluate(obesityPatient("29.0")
                .alt(new BigDecimal("20"))
                .tc(new BigDecimal("4.5"))
                .tg(new BigDecimal("1.2"))
                .fastingGlucose(new BigDecimal("5.0"))
                .build());
        assertEquals("LOW", r.getLevel());
    }

    @Test
    void obesityTakesMaxOfBmiAndStage() {
        assertEquals("MID", obesity.evaluate(obesityPatient("34.0").build()).getLevel());
        assertEquals("MID", obesity.evaluate(obesityPatient("29.0").presentIllness(Set.of("OBESITY", "NAFLD")).build()).getLevel());
        assertEquals("HIGH", obesity.evaluate(obesityPatient("29.0").presentIllness(Set.of("OBESITY", "STROKE")).build()).getLevel());
        assertEquals("VERY_HIGH", obesity.evaluate(obesityPatient("51.0").build()).getLevel());
    }

    @Test
    void obesityDiabetesComplicationIsStage3() {
        AssessmentContext ctx = obesityPatient("29.0")
                .hasDiabetesArchive(true)
                .facts(Map.of(InputField.DM_COMPLICATIONS,
                        new FactValue(List.of("NEPHROPATHY"), "QUESTIONNAIRE_STAFF", LocalDateTime.now())))
                .build();
        assertEquals(AssessmentTier.HIGH, obesity.evaluate(ctx).getTier());
    }
}
