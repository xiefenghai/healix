package com.healix.core.assessment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 一次评估结果（结构化，前端通用渲染）。 */
@Getter
@Setter
public class AssessmentSnapshotDto {
    private String id;
    /** RISK / SEVERITY */
    private String category;
    private String categoryLabel;
    private String engineCode;
    private String engineLabel;
    private String diseaseCode;
    private String diseaseLabel;
    private String rulePackVersion;
    private boolean provisional;
    private String status;
    private String level;
    private String levelLabel;
    /** 统一等级 LOW / MID / HIGH / VERY_HIGH */
    private String tier;
    private String tierLabel;
    /** success / warning / danger / info */
    private String tone;
    private BigDecimal score;
    private String advice;
    private List<Map<String, Object>> dimensions = new ArrayList<>();
    private List<Map<String, Object>> items = new ArrayList<>();
    private List<Map<String, Object>> factorGroups = new ArrayList<>();
    private List<Map<String, Object>> missing = new ArrayList<>();
    private List<Map<String, Object>> comparisons = new ArrayList<>();
    private List<String> notes = new ArrayList<>();
    private Map<String, Object> suspicion;
    private GuidelineDto guideline;
    private Map<String, Object> inputSnapshot;
    private String triggerSource;
    private String triggerLabel;
    private LocalDateTime assessedAt;
    private String assessedByStaffId;
    /** 旧版结构快照（重构前），前端按简版展示 */
    private boolean legacy;

    @Getter
    @Setter
    public static class GuidelineDto {
        private String name;
        private String version;
        private Integer publishedYear;
    }
}
