package com.healix.web.b.assessment;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.QuestionnaireRequestDto;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.ActivityItem;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SendRequestCommand;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SubmitCommand;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SubmitResult;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.View;
import java.util.List;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.service.AssessmentOrchestrator;
import com.healix.core.assessment.service.AssessmentQuestionnaireService;
import com.healix.security.SecurityUtils;
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
    private final AssessmentQuestionnaireService questionnaireService;

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
    public ApiResult<AssessmentSnapshotDto> detail(@PathVariable String peopleId, @PathVariable String id) {
        return ApiResult.ok(assessmentOrchestrator.detail(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId, id));
    }

    @PostMapping("/run")
    public ApiResult<AssessmentOverviewDto> run(@PathVariable String peopleId) {
        return ApiResult.ok(assessmentOrchestrator.runManual(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                SecurityUtils.requireContext().getAccountId()));
    }

    /** 风险评估提示疑似已患病时，一键建对应病种档案。 */
    @PostMapping("/disease-archives/{diseaseCode}")
    public ApiResult<AssessmentOverviewDto> createDiseaseArchive(
            @PathVariable String peopleId, @PathVariable String diseaseCode) {
        return ApiResult.ok(assessmentOrchestrator.createDiseaseArchive(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                diseaseCode));
    }

    @GetMapping("/questionnaire")
    public ApiResult<View> questionnaire(@PathVariable String peopleId) {
        return ApiResult.ok(questionnaireService.viewForStaff(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), peopleId));
    }

    @PostMapping("/questionnaire")
    public ApiResult<SubmitResult> submitQuestionnaire(
            @PathVariable String peopleId, @RequestBody SubmitCommand cmd) {
        return ApiResult.ok(questionnaireService.submitByStaff(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                SecurityUtils.requireContext().getAccountId(),
                cmd));
    }

    @PostMapping("/questionnaire-requests")
    public ApiResult<QuestionnaireRequestDto> sendQuestionnaire(
            @PathVariable String peopleId, @RequestBody(required = false) SendRequestCommand cmd) {
        return ApiResult.ok(questionnaireService.sendRequest(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                cmd == null ? null : cmd.fields()));
    }

    /** 评估问卷活动列表：发送记录 + 提交记录。 */
    @GetMapping("/questionnaire-activity")
    public ApiResult<List<ActivityItem>> questionnaireActivity(
            @PathVariable String peopleId, @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(questionnaireService.listActivity(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                limit));
    }
}
