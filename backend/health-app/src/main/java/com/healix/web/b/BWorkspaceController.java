package com.healix.web.b;

import com.healix.common.result.ApiResult;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.workspace.dto.CareTeamListItem;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.dto.OrgStaffItem;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BWorkspaceController {

    private final OrgWorkspaceService orgWorkspaceService;

    @GetMapping("/org-staff")
    public ApiResult<List<OrgStaffItem>> listOrgStaff(
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String keyword) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listOrgStaff(orgId, roleCode, keyword));
    }

    @PostMapping("/org-staff")
    public ApiResult<OrgStaffItem> createOrgStaff(@RequestBody @Validated CreateOrgStaffRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.createOrgStaff(
                orgId,
                request.username(),
                request.password(),
                request.displayName(),
                request.mobile(),
                request.title(),
                request.roleCode(),
                accountId));
    }

    @DeleteMapping("/org-staff/{staffId}")
    public ApiResult<Void> unbindOrgStaff(@PathVariable String staffId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.unbindOrgStaff(orgId, staffId, accountId);
        return ApiResult.ok(null);
    }

    @GetMapping("/care-teams")
    public ApiResult<List<CareTeamListItem>> listCareTeams(@RequestParam(required = false) String keyword) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listCareTeams(orgId, keyword));
    }

    @GetMapping("/care-teams/{teamId}")
    public ApiResult<CareTeamListItem> getCareTeam(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.getCareTeam(orgId, teamId));
    }

    @PostMapping("/care-teams")
    public ApiResult<CareTeamListItem> createCareTeam(@RequestBody @Validated CreateCareTeamRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.createCareTeam(
                orgId,
                request.name(),
                request.primaryCareManagerStaffId(),
                request.primaryDoctorStaffId(),
                accountId));
    }

    @PutMapping("/care-teams/{teamId}")
    public ApiResult<CareTeamListItem> updateCareTeam(
            @PathVariable String teamId, @RequestBody @Validated UpdateCareTeamRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.updateCareTeamName(orgId, teamId, request.name(), accountId));
    }

    @DeleteMapping("/care-teams/{teamId}")
    public ApiResult<Void> deleteCareTeam(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.deleteCareTeam(orgId, teamId, accountId);
        return ApiResult.ok(null);
    }

    @PutMapping("/care-teams/{teamId}/primary")
    public ApiResult<CareTeamListItem> changePrimary(
            @PathVariable String teamId, @RequestBody @Validated ChangePrimaryRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.changePrimary(
                orgId,
                teamId,
                request.primaryCareManagerStaffId(),
                request.primaryDoctorStaffId(),
                accountId));
    }

    @GetMapping("/care-teams/{teamId}/members")
    public ApiResult<List<CareTeamMember>> listMembers(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listMembers(orgId, teamId));
    }

    @PostMapping("/care-teams/{teamId}/members")
    public ApiResult<CareTeamMember> addMember(
            @PathVariable String teamId, @RequestBody @Validated AddMemberRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.addMember(
                orgId, teamId, request.memberType(), request.staffId(), request.peopleId(), accountId));
    }

    @DeleteMapping("/care-teams/{teamId}/members/{memberId}")
    public ApiResult<Void> removeMember(@PathVariable String teamId, @PathVariable String memberId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.removeMember(orgId, teamId, memberId, accountId);
        return ApiResult.ok(null);
    }

    @GetMapping("/org-patients")
    public ApiResult<List<OrgPatientListItem>> listOrgPatients(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) Boolean unassigned) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listOrgPatients(orgId, keyword, careTeamId, unassigned));
    }

    @PostMapping("/patients/archives")
    public ApiResult<OrgPatientListItem> createArchive(@RequestBody @Validated CreateArchiveRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.createArchive(
                orgId,
                request.name(),
                request.attachPeopleId(),
                Boolean.TRUE.equals(request.noIdentity()),
                request.identityType(),
                request.identityValue(),
                request.gender(),
                request.birthday(),
                accountId));
    }

    public record CreateOrgStaffRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String displayName,
            String mobile,
            String title,
            @NotBlank String roleCode) {
    }

    public record CreateCareTeamRequest(
            @NotBlank String name,
            @NotNull String primaryCareManagerStaffId,
            String primaryDoctorStaffId) {
    }

    public record UpdateCareTeamRequest(@NotBlank String name) {
    }

    public record ChangePrimaryRequest(@NotNull String primaryCareManagerStaffId, String primaryDoctorStaffId) {
    }

    public record AddMemberRequest(@NotBlank String memberType, String staffId, String peopleId) {
    }

    public record CreateArchiveRequest(
            @NotBlank String name,
            String attachPeopleId,
            Boolean noIdentity,
            String identityType,
            String identityValue,
            String gender,
            java.time.LocalDate birthday) {
    }
}
