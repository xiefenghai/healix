package com.healix.core.careplan.guideline;

import java.time.LocalDate;

/**
 * 指南推荐条目。{@code staffText} 是转述，不转载指南原文。
 *
 * @param kind SAFETY 越界不可发布；TARGET 越界警告
 * @param block EXERCISE / DIET / MONITOR / LIFESTYLE
 * @param status DRAFT / REVIEWED / RETIRED；只有 REVIEWED 进生产匹配
 */
public record GuidelineRec(
        String code,
        int rev,
        String sourceCode,
        String section,
        String kind,
        String block,
        GuidelineCondition appliesWhen,
        GuidelineCondition contraindicatedWhen,
        String staffText,
        String patientHint,
        GuidelineQuant quant,
        String evidence,
        int priority,
        String status,
        String reviewer,
        String reviewerTitle,
        LocalDate reviewedAt) {

    public static final String KIND_SAFETY = "SAFETY";
    public static final String KIND_TARGET = "TARGET";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_REVIEWED = "REVIEWED";
    public static final String STATUS_RETIRED = "RETIRED";

    public boolean safety() {
        return KIND_SAFETY.equals(kind);
    }

    /** 方案中承载该条目引用的块：MONITOR / LIFESTYLE 落在执行计划。 */
    public String planBlock() {
        return switch (block) {
            case "EXERCISE" -> "exercise";
            case "DIET" -> "diet";
            default -> "execution";
        };
    }
}
