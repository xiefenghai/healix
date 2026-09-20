package com.healix.core.assessment.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CdrsAssessmentEngineTest {

    private final CdrsAssessmentEngine engine = new CdrsAssessmentEngine();

    @Test
    void highRiskScore() {
        AssessmentContext ctx = baseCtx()
                .ageYears(66)
                .birthday(LocalDate.of(1960, 1, 1))
                .gender("MALE")
                .bmi(new BigDecimal("31.0"))
                .heightCm(new BigDecimal("170"))
                .weightKg(new BigDecimal("90"))
                .waistCm(new BigDecimal("96"))
                .sbp(new BigDecimal("165"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(true)
                .hasDiabetesDiseaseArchive(false)
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .build();
        assertTrue(engine.applicable(ctx));
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertEquals("HIGH", r.getLevel());
        assertTrue(r.getScore().intValue() >= 25);
        assertTrue(Boolean.TRUE.equals(r.getExtras().get("scoreHighRisk")));
    }

    @Test
    void ageGe40MarksHighEvenWhenScoreBelow25() {
        // 42 岁女性、其余指标正常：评分约 11，但年龄≥40 命中高危因素 → HIGH
        AssessmentContext ctx = AssessmentContext.builder()
                .tenantId("t1")
                .peopleId("p1")
                .gender("FEMALE")
                .birthday(LocalDate.of(1984, 1, 1))
                .ageYears(42)
                .heightCm(new BigDecimal("160"))
                .weightKg(new BigDecimal("52"))
                .bmi(new BigDecimal("20.3"))
                .waistCm(new BigDecimal("68"))
                .sbp(new BigDecimal("108"))
                .dbp(new BigDecimal("70"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(false)
                .hasDiabetesDiseaseArchive(false)
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .hdlC(new BigDecimal("1.30"))
                .hdlUnit("mmol/L")
                .tg(new BigDecimal("1.00"))
                .tgUnit("mmol/L")
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertTrue(r.getScore().intValue() < 25);
        assertEquals("HIGH", r.getLevel());
        assertTrue(Boolean.TRUE.equals(r.getExtras().get("factorHighRisk")));
        assertFalse(Boolean.TRUE.equals(r.getExtras().get("scoreHighRisk")));
        assertTrue(r.getAdvice().contains("定期血糖监测"));
    }

    @Test
    void youngWithoutFactorsStaysLowOrMid() {
        AssessmentContext ctx = AssessmentContext.builder()
                .tenantId("t1")
                .peopleId("p1")
                .gender("FEMALE")
                .birthday(LocalDate.of(1998, 1, 1))
                .ageYears(28)
                .heightCm(new BigDecimal("160"))
                .weightKg(new BigDecimal("52"))
                .bmi(new BigDecimal("20.3"))
                .waistCm(new BigDecimal("68"))
                .sbp(new BigDecimal("108"))
                .dbp(new BigDecimal("70"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(false)
                .hasDiabetesDiseaseArchive(false)
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertEquals("LOW", r.getLevel());
        assertFalse(Boolean.TRUE.equals(r.getExtras().get("factorHighRisk")));
        assertFalse(Boolean.TRUE.equals(r.getExtras().get("scoreHighRisk")));
    }

    @Test
    void incompleteWhenMissingWaist() {
        AssessmentContext ctx = baseCtx()
                .waistCm(null)
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(false)
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
        assertTrue(r.getMissingFields().contains("WAIST"));
    }

    @Test
    void notApplicableWhenDiabetesArchive() {
        AssessmentContext ctx = baseCtx().hasDiabetesDiseaseArchive(true).build();
        assertFalse(engine.applicable(ctx));
    }

    @Test
    void notApplicableWhenDiabetesPresentIllness() {
        AssessmentContext ctx = baseCtx().hasDiabetesPresentIllness(true).build();
        assertFalse(engine.applicable(ctx));
    }

    @Test
    void notApplicableWhenAgeOutOfRange() {
        AssessmentContext ctx = baseCtx().ageYears(18).build();
        assertFalse(engine.applicable(ctx));
    }

    @Test
    void boundaryScoresMatchGuidelineTable() {
        assertEquals(0, CdrsAssessmentEngine.scoreAge(20));
        assertEquals(0, CdrsAssessmentEngine.scoreAge(24));
        assertEquals(4, CdrsAssessmentEngine.scoreAge(25));
        assertEquals(11, CdrsAssessmentEngine.scoreAge(42));
        assertEquals(18, CdrsAssessmentEngine.scoreAge(70));

        assertEquals(0, CdrsAssessmentEngine.scoreBmi(new BigDecimal("21.9")));
        assertEquals(1, CdrsAssessmentEngine.scoreBmi(new BigDecimal("22.0")));
        assertEquals(3, CdrsAssessmentEngine.scoreBmi(new BigDecimal("24.0")));
        assertEquals(5, CdrsAssessmentEngine.scoreBmi(new BigDecimal("30.0")));

        assertEquals(0, CdrsAssessmentEngine.scoreWaist(true, new BigDecimal("74.9")));
        assertEquals(8, CdrsAssessmentEngine.scoreWaist(true, new BigDecimal("90.0")));
        assertEquals(10, CdrsAssessmentEngine.scoreWaist(false, new BigDecimal("90.0")));

        assertEquals(0, CdrsAssessmentEngine.scoreSbp(new BigDecimal("109")));
        assertEquals(6, CdrsAssessmentEngine.scoreSbp(new BigDecimal("130")));
        assertEquals(10, CdrsAssessmentEngine.scoreSbp(new BigDecimal("160")));
    }

    private AssessmentContext.AssessmentContextBuilder baseCtx() {
        return AssessmentContext.builder()
                .tenantId("t1")
                .peopleId("p1")
                .gender("FEMALE")
                .birthday(LocalDate.of(1980, 5, 1))
                .ageYears(45)
                .heightCm(new BigDecimal("160"))
                .weightKg(new BigDecimal("60"))
                .bmi(new BigDecimal("23.4"))
                .waistCm(new BigDecimal("78"))
                .sbp(new BigDecimal("118"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(false)
                .hasDiabetesDiseaseArchive(false);
    }
}
