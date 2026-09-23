package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.service.AssessmentOrchestrator;
import com.healix.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端健康评估：只读查看健管师侧已生成的评估结果。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me/assessments")
@RequiredArgsConstructor
public class CAssessmentController {

    private final AssessmentOrchestrator assessmentOrchestrator;

    @GetMapping
    public ApiResult<AssessmentOverviewDto> overview() {
        return ApiResult.ok(assessmentOrchestrator.overviewForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/{id}")
    public ApiResult<AssessmentSnapshotDto> detail(@PathVariable String id) {
        return ApiResult.ok(assessmentOrchestrator.detailForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), id));
    }
}
