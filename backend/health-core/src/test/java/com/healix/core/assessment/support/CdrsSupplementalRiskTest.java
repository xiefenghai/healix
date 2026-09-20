package com.healix.core.assessment.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CdrsSupplementalRiskTest {

    @Test
    void hitsAgeBmiCentralAndFh() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(45)
                .gender("MALE")
                .bmi(new BigDecimal("25.0"))
                .waistCm(new BigDecimal("92"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(true)
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("WEEKLY_3")
                .build();
        Map<String, Object> out = CdrsSupplementalRisk.evaluate(ctx);
        assertEquals(3, out.get("hitCount"));
        assertTrue(Boolean.TRUE.equals(out.get("highRisk")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertTrue(hits.stream().anyMatch(h -> "AGE_GE_40".equals(h.get("code"))));
        assertTrue(hits.stream().anyMatch(h -> "BMI_OR_CENTRAL_OBESITY".equals(h.get("code"))));
        assertTrue(hits.stream().anyMatch(h -> "FIRST_DEGREE_DM_FH".equals(h.get("code"))));
    }

    @Test
    void ageAloneIsHighRisk() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(42)
                .gender("FEMALE")
                .bmi(new BigDecimal("21.0"))
                .waistCm(new BigDecimal("68"))
                .sbp(new BigDecimal("108"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(false)
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .build();
        assertTrue(CdrsSupplementalRisk.isHighRisk(ctx));
        Map<String, Object> out = CdrsSupplementalRisk.evaluate(ctx);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertEquals(1, hits.size());
        assertEquals("AGE_GE_40", hits.get(0).get("code"));
    }

    @Test
    void lipidThresholdInclusive() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(30)
                .gender("FEMALE")
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .hdlC(new BigDecimal("0.91"))
                .hdlUnit("mmol/L")
                .tg(new BigDecimal("2.22"))
                .tgUnit("mmol/L")
                .build();
        Map<String, Object> out = CdrsSupplementalRisk.evaluate(ctx);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertTrue(hits.stream().anyMatch(h -> "DYSLIPIDEMIA".equals(h.get("code"))));
    }

    @Test
    void hypertensionByDbp() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(30)
                .gender("MALE")
                .sbp(new BigDecimal("130"))
                .dbp(new BigDecimal("92"))
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .build();
        Map<String, Object> out = CdrsSupplementalRisk.evaluate(ctx);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hits = (List<Map<String, Object>>) out.get("hits");
        assertTrue(hits.stream().anyMatch(h -> "HYPERTENSION".equals(h.get("code"))));
    }

    @Test
    void youngHealthyNotHighRisk() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(28)
                .gender("FEMALE")
                .bmi(new BigDecimal("21.0"))
                .waistCm(new BigDecimal("68"))
                .sbp(new BigDecimal("108"))
                .dbp(new BigDecimal("70"))
                .familyHistoryCollected(true)
                .firstDegreeDiabetesFamilyHistory(false)
                .pastHistoryCollected(true)
                .prediabetesHistory(false)
                .exerciseCollected(true)
                .exerciseFrequency("DAILY")
                .hdlC(new BigDecimal("1.20"))
                .hdlUnit("mmol/L")
                .tg(new BigDecimal("1.10"))
                .tgUnit("mmol/L")
                .build();
        assertFalse(CdrsSupplementalRisk.isHighRisk(ctx));
    }
}
