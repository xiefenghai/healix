package com.healix.core.assessment.support;

import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.engine.AssessmentEngine.GuidelineRef;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AssessmentResult {
    private final AssessmentStatus status;
    private final String level;
    private final BigDecimal score;
    private final BigDecimal probability;
    private final String advice;
    @Builder.Default
    private final List<AssessmentItem> items = new ArrayList<>();
    @Builder.Default
    private final List<String> missingFields = new ArrayList<>();
    private final GuidelineRef guideline;
    private final String rulePackVersion;
    /** 额外字段（如中心性肥胖） */
    @Builder.Default
    private final Map<String, Object> extras = new LinkedHashMap<>();

    public Map<String, Object> toResultJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("advice", advice);
        m.put("items", items);
        m.put("missingFields", missingFields);
        m.put("extras", extras);
        if (guideline != null) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("name", guideline.name());
            g.put("version", guideline.version());
            g.put("publishedYear", guideline.publishedYear());
            m.put("guideline", g);
        }
        return m;
    }

    @Getter
    @Builder
    public static class AssessmentItem {
        private final String code;
        private final String label;
        private final String inputValue;
        private final Integer points;
        private final String rationale;
    }
}
