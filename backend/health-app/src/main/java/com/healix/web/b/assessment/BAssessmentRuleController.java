package com.healix.web.b.assessment;

import com.healix.common.result.ApiResult;
import com.healix.core.assessment.dto.AssessmentRuleSheetDto;
import com.healix.core.assessment.service.AssessmentOrchestrator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 评估规则说明（由引擎规则表生成，前端不再硬编码）。 */
@RestController
@RequestMapping("/api/b/v1/assessment-rules")
@RequiredArgsConstructor
public class BAssessmentRuleController {

    private final AssessmentOrchestrator assessmentOrchestrator;

    @GetMapping
    public ApiResult<List<AssessmentRuleSheetDto>> rules(@RequestParam(required = false) String engineCode) {
        return ApiResult.ok(assessmentOrchestrator.rules(engineCode));
    }
}
