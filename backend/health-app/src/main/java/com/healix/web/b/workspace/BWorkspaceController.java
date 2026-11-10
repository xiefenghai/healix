package com.healix.web.b.workspace;

import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.workspace.dto.CareTeamDeleteImpact;
import com.healix.core.workspace.dto.CareTeamListItem;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.dto.OrgPatientListStats;
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

    /** 将患者加入当前员工个人重点关注。 */
    @PostMapping("/patients/{peopleId}/watch")
    public ApiResult<OrgPatientListItem> watchPatient(@PathVariable String peopleId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.watchPatient(orgId, peopleId));
    }

    /** 取消当前员工对患者的个人重点关注。 */
    @DeleteMapping("/patients/{peopleId}/watch")
    public ApiResult<OrgPatientListItem> unwatchPatient(@PathVariable String peopleId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.unwatchPatient(orgId, peopleId));
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
                request.mobileOwner(),
                request.address(),
                request.educationLevel(),
                request.maritalStatus(),
                request.occupation(),
                accountId));
    }

    /** 将患者加入或变更到指定健管组（机构内一人仅属一组）。 */
    @PutMapping("/patients/{peopleId}/care-team")
    public ApiResult<OrgPatientListItem> changePatientCareTeam(
            @PathVariable String peopleId, @RequestBody @Validated ChangePatientCareTeamRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.changePatientCareTeam(
                orgId, peopleId, request.careTeamId(), accountId));
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

    /**
     * 列出当前机构照护团队。
     *
     * @param assignableOnly true 时仅返回当前账号可分配患者的组：租户管理员看全部，健管师仅主责组
     */
    @GetMapping("/care-teams")
    public ApiResult<List<CareTeamListItem>> listCareTeams(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean assignableOnly) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        if (Boolean.TRUE.equals(assignableOnly)) {
            return ApiResult.ok(orgWorkspaceService.listAssignableCareTeams(orgId, keyword));
        }
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

    /** 删除健管组前影响预览（患者数 / 未完成待办数）。 */
    @GetMapping("/care-teams/{teamId}/delete-impact")
    public ApiResult<CareTeamDeleteImpact> previewCareTeamDelete(@PathVariable String teamId) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.previewCareTeamDelete(orgId, teamId));
    }

    /**
     * 软删除照护团队。须传 confirmName=组名防误触；患者进公共池，未完成待办待认领，档案/方案保留。
     */
    @DeleteMapping("/care-teams/{teamId}")
    public ApiResult<Void> deleteCareTeam(
            @PathVariable String teamId, @RequestParam String confirmName) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        orgWorkspaceService.deleteCareTeam(orgId, teamId, confirmName, accountId);
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
     * 分页列出当前机构患者。
     *
     * @param keyword    姓名等关键字
     * @param careTeamId 按照护团队筛选
     * @param unassigned 是否仅看未分配团队患者
     * @param watched    是否仅看当前员工重点关注患者
     * @param page       页码，从 1 起
     * @param size       每页条数，最大 100
     */
    @GetMapping("/org-patients")
    public ApiResult<PageResult<OrgPatientListItem>> listOrgPatients(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String careTeamId,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) Boolean watched,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(orgWorkspaceService.pageOrgPatients(
                orgId, keyword, careTeamId, unassigned, watched, page, size));
    }

    /** 患者列表顶栏 KPI（在管 / 未入组 / 关注 / 本月与今日新增）。 */
    @GetMapping("/org-patients/stats")
    public ApiResult<OrgPatientListStats> orgPatientStats() {
        return ApiResult.ok(orgWorkspaceService.orgPatientStats(SecurityUtils.requireCurrentOrgId()));
    }

    /** 建档前查重：同本人号下，姓名+性别+出生日期全匹配可自动关联。 */
    @GetMapping("/patients/archives/duplicates")
    public ApiResult<List<OrgWorkspaceService.DuplicateCandidate>> archiveDuplicates(
            @RequestParam String name,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                            iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE)
                    java.time.LocalDate birthday,
            @RequestParam(required = false) String mobile) {
        return ApiResult.ok(orgWorkspaceService.findPossibleDuplicates(
                SecurityUtils.requireCurrentOrgId(), name, gender, birthday, mobile));
    }

    /** 从当前机构移除患者：仅解除机构关系，档案保留；本机构列表不再可见。 */
    @DeleteMapping("/org-patients/{peopleId}")
    public ApiResult<Void> removeOrgPatient(@PathVariable String peopleId) {
        orgWorkspaceService.removeOrgPatient(
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireContext().getAccountId());
        return ApiResult.ok(null);
    }

    /** 已建档患者补充证件。 */
    @PostMapping("/patients/{peopleId}/identity")
    public ApiResult<OrgPatientListItem> addIdentity(
            @PathVariable String peopleId, @RequestBody @Validated AddIdentityRequest request) {
        return ApiResult.ok(orgWorkspaceService.addIdentity(
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                request.identityType(),
                request.identityValue(),
                SecurityUtils.requireContext().getAccountId()));
    }

    /** 为当前机构建档：证件选填，联系电话须填或标「暂无」；查重确认后可挂接已有档案。 */
    @PostMapping("/patients/archives")
    public ApiResult<OrgPatientListItem> createArchive(@RequestBody @Validated CreateArchiveRequest request) {
        String orgId = SecurityUtils.requireCurrentOrgId();
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(orgWorkspaceService.createArchive(
                orgId,
                request.name(),
                request.attachPeopleId(),
                request.identityType(),
                request.identityValue(),
                request.gender(),
                request.birthday(),
                request.mobile(),
                request.mobileOwner(),
                Boolean.TRUE.equals(request.noMobile()),
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
            String identityType,
            String identityValue,
            String gender,
            java.time.LocalDate birthday,
            @Size(max = 32) String mobile,
            String mobileOwner,
            Boolean noMobile) {
    }

    public record AddIdentityRequest(@NotBlank String identityType, @NotBlank @Size(max = 64) String identityValue) {
    }

    public record UpdatePatientBasicInfoRequest(
            @NotBlank @Size(max = 64) String displayName,
            @Size(max = 32) String mobile,
            String mobileOwner,
            @Size(max = 256) String address,
            @Size(max = 32) String educationLevel,
            @Size(max = 32) String maritalStatus,
            @Size(max = 64) String occupation) {
    }

    public record ChangePatientCareTeamRequest(@NotBlank String careTeamId) {
    }
}
