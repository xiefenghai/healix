package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.assessment.dto.AssessmentOverviewDto.QuestionnaireRequestDto;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SubmitCommand;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SubmitResult;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.View;
import com.healix.core.assessment.service.AssessmentQuestionnaireService;
import com.healix.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** C 端评估问卷：患者只填写，不展示评估结果。 */
@Validated
@RestController
@RequestMapping("/api/c/v1/me/assessments")
@RequiredArgsConstructor
public class CAssessmentController {

    private final AssessmentQuestionnaireService questionnaireService;

    @GetMapping("/questionnaire")
    public ApiResult<View> questionnaire() {
        return ApiResult.ok(questionnaireService.viewForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @PostMapping("/questionnaire")
    public ApiResult<SubmitResult> submit(@RequestBody SubmitCommand cmd) {
        return ApiResult.ok(questionnaireService.submitByPatient(
                SecurityUtils.requireTenantId(),
                SecurityUtils.requirePatientId(),
                SecurityUtils.requireContext().getAccountId(),
                cmd));
    }

    @GetMapping("/questionnaire-request")
    public ApiResult<QuestionnaireRequestDto> pendingRequest() {
        return ApiResult.ok(questionnaireService.pendingForPatient(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }
}
