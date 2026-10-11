package com.healix.web.b.assessment;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentRunRequest;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.service.AssessmentOrchestrator;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/assessments")
@RequiredArgsConstructor
public class BAssessmentController {

    private final AssessmentOrchestrator assessmentOrchestrator;

    @GetMapping
    public ApiResult<AssessmentOverviewDto> overview(@PathVariable String peopleId) {
        return ApiResult.ok(assessmentOrchestrator.overview(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @GetMapping("/history")
    public ApiResult<PageResult<AssessmentSnapshotDto>> history(
            @PathVariable String peopleId,
            @RequestParam(required = false) String engineCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(assessmentOrchestrator.history(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                engineCode,
                page,
                pageSize));
    }

    @GetMapping("/{id}")
    public ApiResult<AssessmentSnapshotDto> detail(
            @PathVariable String peopleId, @PathVariable String id) {
        return ApiResult.ok(assessmentOrchestrator.detail(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId, id));
    }

    @PostMapping("/run")
    public ApiResult<List<AssessmentSnapshotDto>> run(
            @PathVariable String peopleId, @RequestBody(required = false) AssessmentRunRequest req) {
        String engineCode = req == null ? null : req.getEngineCode();
        return ApiResult.ok(assessmentOrchestrator.runManual(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                SecurityUtils.requireContext().getAccountId(),
                engineCode));
    }
}
