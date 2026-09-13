package com.healix.web.b.workspace;

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

/**
 * B 端机构工作台接口。
 * <p>当前机构下的员工、照护团队、患者列表与建档等运营能力。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BWorkspaceController {

    private final OrgWorkspaceService orgWorkspaceService;

    /** 查询当前机构下单个患者摘要（档案页页头：姓名、性别、生日、照护团队等）。 */
    @GetMapping("/patients/{peopleId}")
    public ApiResult<OrgPatientListItem> getPatient(@PathVariable String peopleId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.getOrgPatient(orgId, peopleId));
    }

    /** 更新患者人口学基础信息（姓名、联系方式、住址、文化程度、婚姻、职业）。 */
    @PutMapping("/patients/{peopleId}/basic-info")
    public ApiResult<OrgPatientListItem> updatePatientBasicInfo(
            @PathVariable String peopleId, @RequestBody @Validated UpdatePatientBasicInfoRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.updatePatientBasicInfo(
                orgId,
                peopleId,
                request.displayName(),
                request.mobile(),
                request.address(),
                request.educationLevel(),
                request.maritalStatus(),
                request.occupation(),
                accountId));
    }

    /** 列出当前机构可管员工（可按角色、关键字筛选；用于组队选人等）。 */
    @GetMapping("/org-staff")
    public ApiResult<List<OrgStaffItem>> listOrgStaff(
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String keyword) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listOrgStaff(orgId, roleCode, keyword));
    }

    /** 在当前机构开通员工账号并绑定机构（工作台侧开号）。 */
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

    /** 解除员工与当前机构的绑定（不删除账号本身）。 */
    @DeleteMapping("/org-staff/{staffId}")
    public ApiResult<Void> unbindOrgStaff(@PathVariable String staffId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.unbindOrgStaff(orgId, staffId, accountId);
        return ApiResult.ok(null);
    }

    /** 列出当前机构照护团队。 */
    @GetMapping("/care-teams")
    public ApiResult<List<CareTeamListItem>> listCareTeams(@RequestParam(required = false) String keyword) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listCareTeams(orgId, keyword));
    }

    /** 查询单个照护团队详情。 */
    @GetMapping("/care-teams/{teamId}")
    public ApiResult<CareTeamListItem> getCareTeam(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.getCareTeam(orgId, teamId));
    }

    /** 创建照护团队（指定主责健管师，可选主责医生）。 */
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

    /** 修改照护团队名称。 */
    @PutMapping("/care-teams/{teamId}")
    public ApiResult<CareTeamListItem> updateCareTeam(
            @PathVariable String teamId, @RequestBody @Validated UpdateCareTeamRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.updateCareTeamName(orgId, teamId, request.name(), accountId));
    }

    /** 删除照护团队。 */
    @DeleteMapping("/care-teams/{teamId}")
    public ApiResult<Void> deleteCareTeam(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.deleteCareTeam(orgId, teamId, accountId);
        return ApiResult.ok(null);
    }

    /** 更换照护团队主责健管师 / 主责医生。 */
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

    /** 列出照护团队成员（员工或患者成员）。 */
    @GetMapping("/care-teams/{teamId}/members")
    public ApiResult<List<CareTeamMember>> listMembers(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listMembers(orgId, teamId));
    }

    /** 向照护团队添加成员（员工或患者）。 */
    @PostMapping("/care-teams/{teamId}/members")
    public ApiResult<CareTeamMember> addMember(
            @PathVariable String teamId, @RequestBody @Validated AddMemberRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.addMember(
                orgId, teamId, request.memberType(), request.staffId(), request.peopleId(), accountId));
    }

    /** 从照护团队移除成员。 */
    @DeleteMapping("/care-teams/{teamId}/members/{memberId}")
    public ApiResult<Void> removeMember(@PathVariable String teamId, @PathVariable String memberId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.removeMember(orgId, teamId, memberId, accountId);
        return ApiResult.ok(null);
    }

    /**
     * 列出当前机构患者。
     *
     * @param keyword    姓名等关键字
     * @param careTeamId 按照护团队筛选
     * @param unassigned 是否仅看未分配团队患者
     */
    @GetMapping("/org-patients")
    public ApiResult<List<OrgPatientListItem>> listOrgPatients(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) Boolean unassigned) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.listOrgPatients(orgId, keyword, careTeamId, unassigned));
    }

    /** 为当前机构创建患者档案头（可无证件建档，或挂接已有 people）。 */
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

    public record UpdatePatientBasicInfoRequest(
            @NotBlank @Size(max = 64) String displayName,
            @Size(max = 32) String mobile,
            @Size(max = 256) String address,
            @Size(max = 32) String educationLevel,
            @Size(max = 32) String maritalStatus,
            @Size(max = 64) String occupation) {
    }
}
