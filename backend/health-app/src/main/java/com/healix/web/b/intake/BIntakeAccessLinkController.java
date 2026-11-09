package com.healix.web.b.intake;

import com.healix.common.result.ApiResult;
import com.healix.core.intake.service.IntakeAccessLinkService;
import com.healix.core.questionnaire.service.QuestionnaireResponseService;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端：生成 / 撤销患者首诊填写链接。 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/intake-access-links")
@RequiredArgsConstructor
public class BIntakeAccessLinkController {

    private final IntakeAccessLinkService intakeAccessLinkService;
    private final OrgWorkspaceService orgWorkspaceService;
    private final QuestionnaireResponseService responseService;

    @PostMapping
    public ApiResult<IssueView> issue(
            @PathVariable String peopleId, @RequestBody(required = false) @Validated IssueRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String tenantId = SecurityUtils.requireTenantId();
        String staffId = SecurityUtils.requireStaffId();
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        int days = request != null && request.validDays() != null ? request.validDays() : 7;
        String prefillMode = request != null && StringUtils.hasText(request.prefillMode())
                ? request.prefillMode()
                : QuestionnaireResponseService.RELINK_KEEP;
        try {
            responseService.preparePatientRelinkDraft(tenantId, orgId, peopleId, staffId, prefillMode);
        } catch (Exception e) {
            log.warn("重发链接前准备草稿失败 peopleId={} mode={}: {}", peopleId, prefillMode, e.getMessage());
        }
        IntakeAccessLinkService.IssueResult result =
                intakeAccessLinkService.issue(tenantId, orgId, peopleId, staffId, days);
        return ApiResult.ok(new IssueView(
                result.id(),
                result.token(),
                result.url(),
                result.expireAt(),
                result.copyText(),
                result.orgName(),
                result.patientLabel(),
                result.staffDisplayName(),
                prefillMode));
    }

    @PostMapping("/{linkId}/revoke")
    public ApiResult<Void> revoke(@PathVariable String peopleId, @PathVariable String linkId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        intakeAccessLinkService.revoke(SecurityUtils.requireTenantId(), orgId, peopleId, linkId);
        return ApiResult.ok(null);
    }

    /**
     * @param validDays 有效天数，默认 7
     * @param prefillMode {@code KEEP} 保留上版/当前草稿（推荐）· {@code CLEAR} 清空作答
     */
    public record IssueRequest(@Min(1) @Max(30) Integer validDays, String prefillMode) {}

    public record IssueView(
            String id,
            String token,
            String url,
            LocalDateTime expireAt,
            String copyText,
            String orgName,
            String patientLabel,
            String staffDisplayName,
            String prefillMode) {}
}
