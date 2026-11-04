package com.healix.core.careplan.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CarePlanPromptContextSupportTest {

    @Test
    void filtersPlaceholderDiseaseCodes() {
        assertThat(CarePlanPromptContextSupport.isMeaningfulDiseaseCode("NONE")).isFalse();
        assertThat(CarePlanPromptContextSupport.isMeaningfulDiseaseCode("无")).isFalse();
        assertThat(CarePlanPromptContextSupport.isMeaningfulDiseaseCode("DIABETES")).isTrue();
        assertThat(CarePlanPromptContextSupport.isMeaningfulDiseaseCode("高血压")).isTrue();
    }

    @Test
    void summarizeBasicDropsNoneAndAddsLifestyleLabels() {
        JsonNode content = JsonUtils.readTree(
                """
                {
                  "presentIllness": ["NONE", "DIABETES", "HYPERTENSION"],
                  "diet": {"type": "OTHER", "habit": "FAIR", "preference": "火锅", "note": "111",
                    "restriction": ["NO_SEAFOOD", "NONE"], "sugaryDrinks": "DAILY_1"},
                  "exercise": {"type": "打游戏", "frequency": "NONE", "intensity": "MODERATE", "durationMin": 0},
                  "lifestyle": {"activityLevel": "SEDENTARY", "smoking": {"status": "NEVER"}},
                  "sleep": {"hours": 6, "lateNight": "MOST_DAYS"},
                  "psych": {"stressLevel": "HIGH", "bodyAnxiety": "SEVERE"},
                  "medical": {"exerciseRestriction": ["DOCTOR_LIMIT", "NONE"], "jointIssues": ["KNEE"]},
                  "women": {"reproductiveStatus": "NONE"},
                  "weightHistory": {"triedBefore": "MULTIPLE", "expectedPace": "ASAP"},
                  "medicationFlags": ["ANTIHYPERTENSIVE", "NONE"]
                }
                """);
        JsonNode basic = CarePlanPromptContextSupport.summarizeBasicArchive(content);
        assertThat(basic.path("presentIllness").toString()).doesNotContain("NONE");
        assertThat(basic.path("presentIllnessLabels").toString()).contains("糖尿病", "高血压");
        assertThat(basic.path("diet").path("typeLabel").asText()).isEqualTo("其他");
        assertThat(basic.path("diet").path("habitLabel").asText()).isEqualTo("一般");
        assertThat(basic.path("diet").path("sugaryDrinksLabel").asText()).contains("每天");
        assertThat(basic.path("diet").path("restrictionLabels").toString()).contains("不吃海鲜");
        assertThat(basic.path("diet").path("restriction").toString()).doesNotContain("NONE");
        assertThat(basic.path("exercise").path("frequencyLabel").asText()).isEqualTo("基本不运动");
        assertThat(basic.path("exercise").path("intensityLabel").asText()).isEqualTo("中度");
        assertThat(basic.path("lifestyle").path("activityLevelLabel").asText()).contains("久坐");
        assertThat(basic.path("lifestyle").path("smoking").path("statusLabel").asText()).isEqualTo("从不");
        assertThat(basic.path("sleep").path("lateNightLabel").asText()).contains("大多数");
        assertThat(basic.path("psych").path("stressLevelLabel").asText()).contains("压力");
        assertThat(basic.path("psych").path("bodyAnxietyLabel").asText()).contains("经常");
        assertThat(basic.path("medical").path("jointIssuesLabels").toString()).contains("膝");
        assertThat(basic.path("women").path("reproductiveStatusLabel").asText()).isEqualTo("都没有");
        assertThat(basic.path("weightHistory").path("triedBeforeLabel").asText()).contains("多次");
        assertThat(basic.path("medicationFlagsLabels").toString()).contains("降压");
    }

    @Test
    void formatsLabScientificUnits() {
        String summary = CarePlanPromptContextSupport.formatLabItemSummary(
                "白细胞计数", new BigDecimal("7.5500"), null, "10^9/L");
        assertThat(summary).isEqualTo("白细胞计数=7.55×10^9/L");

        String plain = CarePlanPromptContextSupport.formatLabItemSummary(
                "血红蛋白", new BigDecimal("145.0000"), null, "g/L");
        assertThat(plain).isEqualTo("血红蛋白=145 g/L");
    }

    @Test
    void skipsAbsurdWaistForPrompt() {
        assertThat(CarePlanPromptContextSupport.isMetricUsefulForPrompt("WAIST", new BigDecimal("40")))
                .isFalse();
        assertThat(CarePlanPromptContextSupport.isMetricUsefulForPrompt("WAIST", new BigDecimal("86")))
                .isTrue();
        assertThat(CarePlanPromptContextSupport.formatMetricValue(new BigDecimal("60.0000")))
                .isEqualTo("60");
        assertThat(CarePlanPromptContextSupport.suspectMetricNote("WAIST", new BigDecimal("40"), "cm"))
                .contains("腰围")
                .contains("已忽略");
    }
}
