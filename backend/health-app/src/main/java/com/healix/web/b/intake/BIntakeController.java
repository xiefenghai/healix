package com.healix.web.b.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.program.domain.PeopleProgramEnrollment;
import com.healix.core.program.service.ProgramEnrollmentService;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.SensitiveFieldMask;
import com.healix.core.questionnaire.dto.IntakeStatusView;
import com.healix.core.questionnaire.dto.QuestionnaireResponseView;
import com.healix.core.questionnaire.service.QuestionnaireResponseService;
import com.healix.security.SecurityUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端首诊问卷工作台接口。 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BIntakeController {

    private final ArchiveAccessService archiveAccessService;
    private final QuestionnaireResponseService responseService;
    private final ProgramEnrollmentService enrollmentService;
    private final FollowupService followupService;
    private final FieldCatalog fieldCatalog;

    /** 客户页首诊卡片：待补题数。 */
    @GetMapping("/patients/{peopleId}/intake/status")
    public ApiResult<IntakeStatusView> intakeStatus(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(responseService.intakeStatus(tenantId, peopleId));
    }

    /** 打开 / 续开首诊作答草稿。 */
    @PostMapping("/patients/{peopleId}/intake/open")
    public ApiResult<QuestionnaireResponseView> open(
            @PathVariable String peopleId, @RequestBody(required = false) OpenIntakeRequest body) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        String staffId = SecurityUtils.requireStaffId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        OpenIntakeRequest req = body == null ? new OpenIntakeRequest(null, null, null) : body;
        return ApiResult.ok(maskIfNeeded(responseService.openIntake(
                tenantId, orgId, peopleId, staffId, req.followupRecordId(), req.programCode(), req.channel())));
    }

    /** 打开升版 / 后补作答（仅待补题）。 */
    @PostMapping("/patients/{peopleId}/intake/supplement/open")
    public ApiResult<QuestionnaireResponseView> openSupplement(
            @PathVariable String peopleId, @RequestBody(required = false) OpenIntakeRequest body) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        String staffId = SecurityUtils.requireStaffId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        String channel = body == null ? null : body.channel();
        return ApiResult.ok(maskIfNeeded(
                responseService.openSupplement(tenantId, orgId, peopleId, staffId, channel)));
    }

    @GetMapping("/questionnaire-responses/{id}")
    public ApiResult<QuestionnaireResponseView> get(@PathVariable String id) {
        QuestionnaireResponseView view = responseService.get(id);
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, view.getPeopleId());
        return ApiResult.ok(maskIfNeeded(view));
    }

    private QuestionnaireResponseView maskIfNeeded(QuestionnaireResponseView view) {
        boolean canView = SensitiveFieldMask.anyRoleCanView(SecurityUtils.requireContext().getRoles());
        if (canView || view == null) {
            return view;
        }
        if (view.getAnswers() != null && view.getAnswers().isObject()) {
            Map<String, JsonNode> flat = new LinkedHashMap<>();
            view.getAnswers().fields().forEachRemaining(e -> flat.put(e.getKey(), e.getValue()));
            SensitiveFieldMask.maskFlat(flat, fieldCatalog, false);
            view.setAnswers(JsonUtils.mapper().valueToTree(flat));
        }
        if (view.getHighlights() != null) {
            view.setHighlights(SensitiveFieldMask.maskHighlights(view.getHighlights(), fieldCatalog, false));
        }
        return view;
    }

    @PutMapping("/questionnaire-responses/{id}/draft")
    public ApiResult<QuestionnaireResponseView> saveDraft(
            @PathVariable String id, @RequestBody SaveDraftRequest body) {
        String staffId = SecurityUtils.requireStaffId();
        QuestionnaireResponseView existing = responseService.get(id);
        archiveAccessService.assertStaffCanAccessPeople(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), existing.getPeopleId());
        return ApiResult.ok(responseService.saveDraft(
                id,
                staffId,
                JsonUtils.toJson(body.answers() == null ? JsonUtils.emptyObject() : body.answers()),
                body.programCode(),
                body.channel()));
    }

    @PostMapping("/questionnaire-responses/{id}/submit")
    public ApiResult<QuestionnaireResponseView> submit(
            @PathVariable String id, @RequestBody(required = false) SubmitRequest body) {
        String staffId = SecurityUtils.requireStaffId();
        QuestionnaireResponseView existing = responseService.get(id);
        archiveAccessService.assertStaffCanAccessPeople(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), existing.getPeopleId());
        Map<String, JsonNode> resolutions = new LinkedHashMap<>();
        if (body != null && body.conflictResolutions() != null) {
            body.conflictResolutions().fields().forEachRemaining(e -> resolutions.put(e.getKey(), e.getValue()));
        }
        QuestionnaireResponseView submitted = responseService.submit(id, staffId, resolutions);
        if (submitted.getFollowupRecordId() != null && !submitted.getFollowupRecordId().isBlank()) {
            try {
                followupService.completeOnboardingByIntake(
                        SecurityUtils.requireCurrentOrgId(),
                        submitted.getFollowupRecordId(),
                        submitted.getId(),
                        submitted.getChannel(),
                        SecurityUtils.requireContext().getAccountId());
            } catch (Exception e) {
                log.warn("首诊作答已提交，但自动办结随访失败 responseId={}: {}", submitted.getId(), e.getMessage());
            }
        }
        return ApiResult.ok(submitted);
    }

    @GetMapping("/patients/{peopleId}/enrollments")
    public ApiResult<List<PeopleProgramEnrollment>> enrollments(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return ApiResult.ok(enrollmentService.list(tenantId, orgId, peopleId));
    }

    @GetMapping("/patients/{peopleId}/weight-loss-plan-gate")
    public ApiResult<Map<String, Object>> weightLossPlanGate(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        boolean ok = enrollmentService.canGenerateWeightLossPlan(tenantId, orgId, peopleId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("allowed", ok);
        out.put("reason", ok ? null : "请先完成减重项目首诊问卷");
        return ApiResult.ok(out);
    }

    public record OpenIntakeRequest(String followupRecordId, String programCode, String channel) {}

    public record SaveDraftRequest(JsonNode answers, String programCode, String channel) {}

    public record SubmitRequest(JsonNode conflictResolutions) {}
}
