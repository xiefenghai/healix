package com.healix.web.b.activation;

import com.healix.common.result.ApiResult;
import com.healix.core.patientcard.domain.PeopleActivationInvite;
import com.healix.core.patientcard.service.PatientActivationService;
import com.healix.core.patientcard.service.PatientCardService;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端患者激活码。 */
@Validated
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/activation-invites")
@RequiredArgsConstructor
public class BPatientActivationController {

    private final PatientActivationService patientActivationService;
    private final PatientCardService patientCardService;
    private final OrgWorkspaceService orgWorkspaceService;

    @PostMapping
    public ApiResult<InviteView> create(
            @PathVariable String peopleId, @RequestBody(required = false) @Validated CreateInviteRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        // ensure patient in org
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        int days = request != null && request.validDays() != null ? request.validDays() : 7;
        PeopleActivationInvite invite = patientActivationService.issue(
                SecurityUtils.requireTenantId(),
                orgId,
                peopleId,
                SecurityUtils.requireStaffId(),
                days);
        return ApiResult.ok(toView(invite));
    }

    @GetMapping
    public ApiResult<List<InviteView>> list(@PathVariable String peopleId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        return ApiResult.ok(patientActivationService.listByPeople(peopleId).stream().map(this::toView).toList());
    }

    @DeleteMapping("/{inviteId}")
    public ApiResult<Void> revoke(@PathVariable String peopleId, @PathVariable String inviteId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        patientActivationService.revoke(peopleId, inviteId);
        return ApiResult.ok(null);
    }

    @GetMapping("/status")
    public ApiResult<CLinkStatus> status(@PathVariable String peopleId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        orgWorkspaceService.getOrgPatient(orgId, peopleId);
        int count = patientCardService.countLinkedAccounts(peopleId);
        return ApiResult.ok(new CLinkStatus(count > 0, count));
    }

    private InviteView toView(PeopleActivationInvite invite) {
        boolean used = invite.getUsedAt() != null;
        boolean expired =
                invite.getExpireAt() != null && invite.getExpireAt().isBefore(LocalDateTime.now());
        return new InviteView(
                invite.getId(),
                invite.getCode(),
                invite.getPeopleId(),
                invite.getOrgId(),
                invite.getExpireAt(),
                used,
                expired,
                invite.getEnabled() != null && invite.getEnabled() == 1 && !used && !expired);
    }

    public record CreateInviteRequest(@Min(1) @Max(90) Integer validDays) {}

    public record InviteView(
            String id,
            String code,
            String peopleId,
            String orgId,
            LocalDateTime expireAt,
            boolean used,
            boolean expired,
            boolean active) {}

    public record CLinkStatus(boolean linked, int cardCount) {}
}
