package com.healix.core.assessment.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObesityScreenAssessmentEngineTest {

    private final ObesityScreenAssessmentEngine engine = new ObesityScreenAssessmentEngine();

    @Test
    void mildObesityWithCentral() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(40)
                .gender("MALE")
                .bmi(new BigDecimal("30.0"))
                .heightCm(new BigDecimal("170"))
                .weightKg(new BigDecimal("87"))
                .waistCm(new BigDecimal("92"))
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertEquals("MILD_OBESITY", r.getLevel());
        assertEquals("OBESITY", r.getExtras().get("diagnosis"));
        assertTrue(Boolean.TRUE.equals(r.getExtras().get("centralObesity")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dims = (List<Map<String, Object>>) r.getExtras().get("dimensions");
        assertEquals(3, dims.size());
    }

    @Test
    void overweightWithoutWaistStillComplete() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(35)
                .gender("FEMALE")
                .bmi(new BigDecimal("26.0"))
                .heightCm(new BigDecimal("160"))
                .weightKg(new BigDecimal("66.5"))
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.COMPLETE, r.getStatus());
        assertEquals("OVERWEIGHT", r.getExtras().get("diagnosis"));
        assertNull(r.getExtras().get("centralObesity"));
    }

    @Test
    void normalBmiWithCentralObesity() {
        AssessmentContext ctx = AssessmentContext.builder()
                .ageYears(42)
                .gender("MALE")
                .bmi(new BigDecimal("23.0"))
                .waistCm(new BigDecimal("91"))
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals("NORMAL", r.getExtras().get("diagnosis"));
        assertTrue(Boolean.TRUE.equals(r.getExtras().get("centralObesity")));
        assertTrue(r.getAdvice().contains("中心性肥胖"));
    }

    @Test
    void waistElevatedButNotCentral() {
        assertEquals("ELEVATED", ObesityScreenAssessmentEngine.waistBand(true, new BigDecimal("87")));
        assertFalse(ObesityScreenAssessmentEngine.isCentralObesity(true, new BigDecimal("87")));
        assertEquals("CENTRAL", ObesityScreenAssessmentEngine.waistBand(false, new BigDecimal("85")));
    }

    @Test
    void incompleteWithoutBmi() {
        AssessmentContext ctx = AssessmentContext.builder()
                .gender("FEMALE")
                .waistCm(new BigDecimal("80"))
                .build();
        AssessmentResult r = engine.evaluate(ctx);
        assertEquals(AssessmentStatus.INCOMPLETE, r.getStatus());
        assertTrue(r.getMissingFields().contains("HEIGHT_WEIGHT"));
    }

    @Test
    void notApplicableForChildren() {
        AssessmentContext ctx = AssessmentContext.builder().ageYears(12).gender("MALE").build();
        assertFalse(engine.applicable(ctx));
    }

    @Test
    void notApplicableWhenKnownObesity() {
        assertFalse(engine.applicable(AssessmentContext.builder()
                .ageYears(40)
                .hasObesityDiseaseArchive(true)
                .build()));
        assertFalse(engine.applicable(AssessmentContext.builder()
                .ageYears(40)
                .hasObesityPresentIllness(true)
                .build()));
    }

    @Test
    void diagnoseThresholdsMatchGuideline() {
        assertEquals("NORMAL", ObesityScreenAssessmentEngine.diagnoseByBmi(new BigDecimal("23.9")));
        assertEquals("OVERWEIGHT", ObesityScreenAssessmentEngine.diagnoseByBmi(new BigDecimal("24.0")));
        assertEquals("OVERWEIGHT", ObesityScreenAssessmentEngine.diagnoseByBmi(new BigDecimal("27.9")));
        assertEquals("OBESITY", ObesityScreenAssessmentEngine.diagnoseByBmi(new BigDecimal("28.0")));
    }
}
