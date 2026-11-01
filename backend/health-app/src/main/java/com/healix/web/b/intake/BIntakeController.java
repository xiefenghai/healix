package com.healix.web.b.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.followup.FollowupSummaryGenerator.FollowupSummaryDraft;
import com.healix.common.result.ApiResult;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.program.domain.PeopleProgramEnrollment;
import com.healix.core.program.service.ProgramEnrollmentService;
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
    private final IntakeAiSummaryService intakeAiSummaryService;
    private final PersonaAppService personaAppService;

    /** 客户页首诊卡片：待补题数。 */
    @GetMapping("/patients/{peopleId}/intake/status")
    public ApiResult<IntakeStatusView> intakeStatus(@PathVariable String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        IntakeStatusView view = responseService.intakeStatus(tenantId, peopleId);
        // 兼容：详情页开首诊未挂 followupId 时，提交后任务可能仍 OPEN；有已提交答卷则补办结
        if (view.isHasSubmitted() && StringUtils.hasText(view.getLatestSubmittedId())) {
            try {
                followupService.completeOnboardingAfterIntakeSubmit(
                        orgId,
                        peopleId,
                        null,
                        view.getLatestSubmittedId(),
                        null,
                        SecurityUtils.requireContext().getAccountId(),
                        null);
            } catch (Exception e) {
                log.warn(
                        "首诊已提交，补办结入组随访失败 peopleId={}: {}",
                        peopleId,
                        e.getMessage());
            }
        }
        return ApiResult.ok(view);
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
        // 作答打开不做敏感脱敏：脱敏成「已采集」会进草稿/预填，提交后写脏档案
        return ApiResult.ok(responseService.openIntake(
                tenantId, orgId, peopleId, staffId, req.followupRecordId(), req.programCode(), req.channel()));
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
        return ApiResult.ok(
                responseService.openSupplement(tenantId, orgId, peopleId, staffId, channel));
    }

    /**
     * 只读详情：返回当时填写内容（含敏感字段原文）。
     * <p>档案页仍按角色脱敏；首诊作答读写链路不做脱敏，避免「已采集」写回元数据。
     */
    @GetMapping("/questionnaire-responses/{id}")
    public ApiResult<QuestionnaireResponseView> get(@PathVariable String id) {
        QuestionnaireResponseView view = responseService.get(id);
        String tenantId = SecurityUtils.requireTenantId();
        String orgId = SecurityUtils.requireCurrentOrgId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, view.getPeopleId());
        return ApiResult.ok(view);
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
        String orgId = SecurityUtils.requireCurrentOrgId();
        String tenantId = SecurityUtils.requireTenantId();
        // 入组/首诊：提交后办结 OPEN 的 ONBOARDING 随访（详情页开首诊常无 followupRecordId）
        if (QuestionnaireResponseService.PURPOSE_INTAKE.equals(submitted.getPurpose())) {
            try {
                String aiSummary = extractAiSummary(submitted.getAnswers());
                followupService.completeOnboardingAfterIntakeSubmit(
                        orgId,
                        submitted.getPeopleId(),
                        submitted.getFollowupRecordId(),
                        submitted.getId(),
                        submitted.getChannel(),
                        SecurityUtils.requireContext().getAccountId(),
                        aiSummary);
            } catch (Exception e) {
                log.warn("首诊作答已提交，但自动办结随访失败 responseId={}: {}", submitted.getId(), e.getMessage());
            }
        }
        if (QuestionnaireResponseService.PURPOSE_INTAKE.equals(submitted.getPurpose())
                || QuestionnaireResponseService.PURPOSE_SUPPLEMENT.equals(submitted.getPurpose())) {
            try {
                personaAppService.scheduleAfterIntakeSubmit(tenantId, orgId, submitted.getPeopleId());
            } catch (Exception e) {
                log.warn("首诊提交后触发用户画像失败 peopleId={}: {}", submitted.getPeopleId(), e.getMessage());
            }
        }
        return ApiResult.ok(submitted);
    }

    /** 根据当前草稿/已填答案生成 AI 案头总结（不自动落库；前端写入 staff.aiSummary 后随草稿/提交保存）。 */
    @PostMapping("/questionnaire-responses/{id}/ai-summary")
    public ApiResult<AiSummaryResponse> aiSummary(
            @PathVariable String id, @RequestBody(required = false) AiSummaryRequest body) {
        QuestionnaireResponseView existing = responseService.get(id);
        archiveAccessService.assertStaffCanAccessPeople(
                SecurityUtils.requireTenantId(), SecurityUtils.requireCurrentOrgId(), existing.getPeopleId());
        JsonNode answersOverride = body == null ? null : body.answers();
        FollowupSummaryDraft draft =
                intakeAiSummaryService.generate(id, SecurityUtils.requireTenantId(), answersOverride);
        return ApiResult.ok(new AiSummaryResponse(
                draft.summary(),
                draft.keyPoints(),
                draft.risks(),
                draft.nextActions(),
                draft.fromLlm(),
                draft.note()));
    }

    private static String extractAiSummary(JsonNode answers) {
        if (answers == null || !answers.isObject()) {
            return null;
        }
        JsonNode n = answers.get(IntakeAiSummaryService.FIELD_AI_SUMMARY);
        if (n == null || n.isNull() || !StringUtils.hasText(n.asText())) {
            return null;
        }
        return n.asText().trim();
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

    public record AiSummaryRequest(JsonNode answers) {}

    public record AiSummaryResponse(
            String summary,
            List<String> keyPoints,
            List<String> risks,
            List<String> nextActions,
            boolean fromLlm,
            String note) {}
}
