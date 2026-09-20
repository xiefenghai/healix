package com.healix.core.assessment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssessmentSnapshotDto {
    private String id;
    private String kind;
    /** 对外展示：疾病风险等级评估 / 疾病分层评估 */
    private String kindLabel;
    private String engineCode;
    private String engineLabel;
    private String diseaseCode;
    private String rulePackVersion;
    private String status;
    private String level;
    private String levelLabel;
    private BigDecimal score;
    private BigDecimal probability;
    private String advice;
    private List<ItemDto> items = new ArrayList<>();
    private List<String> missingFields = new ArrayList<>();
    private Map<String, Object> extras;
    private GuidelineDto guideline;
    private Map<String, Object> inputSnapshot;
    private String triggerSource;
    private LocalDateTime assessedAt;
    private String assessedByStaffId;

    @Getter
    @Setter
    public static class ItemDto {
        private String code;
        private String label;
        private String inputValue;
        private Integer points;
        private String rationale;
    }

    @Getter
    @Setter
    public static class GuidelineDto {
        private String name;
        private String version;
        private Integer publishedYear;
    }
}
