package com.healix.web.b.intake;

import com.healix.common.result.ApiResult;
import com.healix.core.intake.service.IntakeAccessLinkService;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端：生成 / 撤销患者首诊填写链接。 */
@Validated
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/intake-access-links")
@RequiredArgsConstructor
public class BIntakeAccessLinkController {

    private final IntakeAccessLinkService intakeAccessLinkService;
    private final OrgWorkspaceService orgWorkspaceService;

    @PostMapping
    public ApiResult<IssueView> issue(
            @PathVariable String peopleId, @RequestBody(required = false) @Validated IssueRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        int days = request != null && request.validDays() != null ? request.validDays() : 7;
        IntakeAccessLinkService.IssueResult result = intakeAccessLinkService.issue(
                SecurityUtils.requireTenantId(),
                orgId,
                peopleId,
                SecurityUtils.requireStaffId(),
                days);
        return ApiResult.ok(new IssueView(
                result.id(),
                result.token(),
                result.url(),
                result.expireAt(),
                result.copyText()));
    }

    @PostMapping("/{linkId}/revoke")
    public ApiResult<Void> revoke(@PathVariable String peopleId, @PathVariable String linkId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        intakeAccessLinkService.revoke(SecurityUtils.requireTenantId(), orgId, peopleId, linkId);
        return ApiResult.ok(null);
    }

    public record IssueRequest(@Min(1) @Max(30) Integer validDays) {}

    public record IssueView(String id, String token, String url, LocalDateTime expireAt, String copyText) {}
}
