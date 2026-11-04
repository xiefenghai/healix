package com.healix.core.assessment.dto;

import com.healix.core.assessment.support.RuleSheet;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 引擎规则说明（前端通用渲染）。 */
@Getter
@Setter
public class AssessmentRuleSheetDto {
    private String engineCode;
    private String engineLabel;
    private String category;
    private String categoryLabel;
    private String diseaseCode;
    private String diseaseLabel;
    private String guidelineName;
    private String guidelineVersion;
    private String rulePackVersion;
    private boolean provisional;
    private List<LevelDto> levels;
    private RuleSheet sheet;

    public record LevelDto(String code, String label, String tier, String tierLabel, String tone) {}
}
