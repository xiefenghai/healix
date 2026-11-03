package com.healix.web.c;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.questionnaire.IntakeDepth;
import com.healix.core.questionnaire.dto.IntakeStatusView;
import com.healix.core.questionnaire.dto.QuestionnaireResponseView;
import com.healix.core.questionnaire.service.QuestionnaireResponseService;
import com.healix.security.SecurityUtils;
import com.healix.web.b.persona.PersonaAppService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端首诊三档问卷：极简 / 快速 / 完整。
 *
 * <p>复用 B 端同一套 {@code WL_INTAKE} 作答表；按 {@link IntakeDepth} 过滤题目。
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/c/v1/me/intake")
@RequiredArgsConstructor
public class CIntakeController {

    private final QuestionnaireResponseService responseService;
    private final FollowupService followupService;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final PersonaAppService personaAppService;

    @GetMapping("/status")
    public ApiResult<IntakeStatusView> status() {
        String tenantId = SecurityUtils.requireTenantId();
        String peopleId = SecurityUtils.requirePatientId();
        return ApiResult.ok(responseService.intakeStatus(tenantId, peopleId));
    }

    /** 打开 / 续开草稿；depth 默认极简，可切快速/完整。 */
    @GetMapping
    public ApiResult<QuestionnaireResponseView> open(
            @RequestParam(required = false, defaultValue = "MINIMAL") String depth) {
        String tenantId = SecurityUtils.requireTenantId();
        String peopleId = SecurityUtils.requirePatientId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        String orgId = requirePrimaryOrgId(tenantId, peopleId);
        IntakeDepth d = parseDepth(depth);
        return ApiResult.ok(responseService.openPatientIntake(tenantId, orgId, peopleId, accountId, d));
    }

    @PutMapping("/draft")
    public ApiResult<QuestionnaireResponseView> saveDraft(@RequestBody SaveDraftBody body) {
        String peopleId = SecurityUtils.requirePatientId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        if (body == null || !StringUtils.hasText(body.id())) {
            throw new BusinessException(400, "缺少作答 ID");
        }
        IntakeDepth d = parseDepth(body.depth());
        String answersJson = JsonUtils.toJson(body.answers() == null ? JsonUtils.emptyObject() : body.answers());
        return ApiResult.ok(responseService.savePatientDraft(body.id(), peopleId, accountId, answersJson, d));
    }

    @PostMapping("/submit")
    public ApiResult<QuestionnaireResponseView> submit(@RequestBody SubmitBody body) {
        String tenantId = SecurityUtils.requireTenantId();
        String peopleId = SecurityUtils.requirePatientId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        if (body == null || !StringUtils.hasText(body.id())) {
            throw new BusinessException(400, "缺少作答 ID");
        }
        IntakeDepth d = parseDepth(body.depth());
        // 提交前若带了最新答案，先落草稿
        if (body.answers() != null) {
            responseService.savePatientDraft(
                    body.id(), peopleId, accountId, JsonUtils.toJson(body.answers()), d);
        }
        Map<String, JsonNode> resolutions = new LinkedHashMap<>();
        if (body.conflictResolutions() != null) {
            body.conflictResolutions()
                    .fields()
                    .forEachRemaining(e -> resolutions.put(e.getKey(), e.getValue()));
        }
        QuestionnaireResponseView submitted =
                responseService.submitPatientIntake(body.id(), peopleId, accountId, d, resolutions);
        String orgId = submitted.getOrgId();
        if (!StringUtils.hasText(orgId)) {
            orgId = requirePrimaryOrgId(tenantId, peopleId);
        }
        try {
            followupService.completeOnboardingAfterIntakeSubmit(
                    orgId,
                    peopleId,
                    submitted.getFollowupRecordId(),
                    submitted.getId(),
                    submitted.getChannel(),
                    accountId,
                    null);
        } catch (Exception e) {
            log.warn("C 端首诊已提交，办结入组随访失败 responseId={}: {}", submitted.getId(), e.getMessage());
        }
        try {
            personaAppService.scheduleAfterIntakeSubmit(tenantId, orgId, peopleId, null, accountId);
        } catch (Exception e) {
            log.warn("C 端首诊提交后触发用户画像失败 peopleId={}: {}", peopleId, e.getMessage());
        }
        return ApiResult.ok(submitted);
    }

    private static IntakeDepth parseDepth(String raw) {
        try {
            return IntakeDepth.require(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
    }

    private String requirePrimaryOrgId(String tenantId, String peopleId) {
        // Magic Link JWT 已绑定 org，优先使用，避免与主责机构不一致
        String fromJwt = SecurityUtils.requireContext().getCurrentOrgId();
        if (StringUtils.hasText(fromJwt)) {
            return fromJwt;
        }
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        if (a != null && StringUtils.hasText(a.getPrimaryOrgId())) {
            return a.getPrimaryOrgId();
        }
        List<PatientOrgMembership> ms = membershipMapper.listActiveByPeople(peopleId);
        if (ms == null || ms.isEmpty() || !StringUtils.hasText(ms.get(0).getOrgId())) {
            throw new BusinessException(400, "尚未加入健管机构，暂无法填写首诊");
        }
        return ms.get(0).getOrgId();
    }

    public record SaveDraftBody(String id, String depth, JsonNode answers) {}

    public record SubmitBody(String id, String depth, JsonNode answers, JsonNode conflictResolutions) {}
}
