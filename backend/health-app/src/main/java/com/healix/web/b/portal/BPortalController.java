package com.healix.web.b.portal;

import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.identity.domain.StaffAccount;
import com.healix.core.identity.domain.StaffOrgBinding;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.govern.service.FeatureFlagService.FlagView;
import com.healix.core.govern.service.TenantConfigService;
import com.healix.core.govern.service.TenantConfigService.BrandingView;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.identity.service.MfaService;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.ops.dto.StaffAccountListItem;
import com.healix.core.org.domain.OrgInviteCode;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.service.OrganizationService;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.service.MembershipService;
import com.healix.core.tenantadmin.service.TenantAdminService;
import com.healix.core.tenantadmin.service.TenantAdminService.RegisterStaffCommand;
import com.healix.core.tenantadmin.service.TenantAdminService.ReplaceOrgsResult;
import com.healix.core.tenantadmin.service.TenantAdminService.ReplaceRoleResult;
import com.healix.core.tenantadmin.service.TenantAdminService.StaffListQuery;
import com.healix.core.tenantadmin.service.TenantAdminService.StaffRegisterResult;
import com.healix.core.tenantadmin.service.TenantAdminService.StaffUpdateResult;
import com.healix.core.tenantadmin.service.TenantAdminService.UpdateStaffCommand;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.service.VitalService;
import com.healix.security.JwtTokenProvider;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端门户接口。
 * <p>员工登录 / 会话、租户侧机构与账号治理，以及患者体征查询等。
 */
@Validated
@RestController
@RequestMapping("/api/b/v1")
@RequiredArgsConstructor
public class BPortalController {

    private final IdentityService identityService;
    private final OrganizationService organizationService;
    private final MembershipService membershipService;
    private final VitalService vitalService;
    private final TenantAdminService tenantAdminService;
    private final JwtTokenProvider jwtTokenProvider;
    private final TenantConfigService tenantConfigService;
    private final FeatureFlagService featureFlagService;
    private final MfaService mfaService;
    private final AuditService auditService;

    /** 员工登录，签发 Staff JWT（含租户、默认机构、角色）。 */
    @PostMapping("/auth/login")
    public ApiResult<StaffTokenResponse> login(@RequestBody @Validated LoginRequest request) {
        StaffAccount account = identityService.requireStaffAccount(request.username());
        if (!identityService.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        mfaService.assertLoginCode(MfaService.TYPE_STAFF, account.getId(), request.mfaCode());
        StaffProfile profile = identityService.requireStaffByAccount(account.getId());
        Set<String> roles = identityService.staffRoles(profile.getId());
        List<StaffOrgBinding> orgs = identityService.staffOrgs(profile.getId());
        String currentOrgId = orgs.stream()
                .filter(o -> o.getIsDefault() != null && o.getIsDefault() == 1)
                .map(StaffOrgBinding::getOrgId)
                .findFirst()
                .orElse(orgs.isEmpty() ? null : orgs.get(0).getOrgId());
        String token = jwtTokenProvider.createStaffToken(
                account.getId(), profile.getId(), profile.getTenantId(), currentOrgId, roles);
        return ApiResult.ok(new StaffTokenResponse(
                token, account.getId(), profile.getId(), profile.getTenantId(), currentOrgId, roles));
    }

    /** 切换当前工作机构，重新签发 JWT（租户管理员可切本租户任意机构）。 */
    @PutMapping("/session/org")
    public ApiResult<StaffTokenResponse> switchOrg(@RequestBody @Validated SwitchOrgRequest request) {
        var ctx = SecurityUtils.requireContext();
        Organization org = organizationService.require(request.orgId());
        if (!ctx.getTenantId().equals(org.getTenantId())) {
            throw new BusinessException("机构不属于当前租户");
        }
        if (!ctx.getRoles().contains(StaffRoleEnum.TENANT_ADMIN.name())) {
            identityService.assertStaffInOrg(ctx.getStaffId(), request.orgId());
        }
        Set<String> roles = identityService.staffRoles(ctx.getStaffId());
        String token = jwtTokenProvider.createStaffToken(
                ctx.getAccountId(), ctx.getStaffId(), ctx.getTenantId(), request.orgId(), roles);
        return ApiResult.ok(new StaffTokenResponse(
                token, ctx.getAccountId(), ctx.getStaffId(), ctx.getTenantId(), request.orgId(), roles));
    }

    /** 当前登录员工会话信息（资料、角色、可访问机构列表）。 */
    @GetMapping("/session/me")
    public ApiResult<StaffSessionResponse> me() {
        var ctx = SecurityUtils.requireContext();
        StaffProfile profile = identityService.requireStaff(ctx.getStaffId());
        Set<String> roles = identityService.staffRoles(ctx.getStaffId());
        List<OrgBrief> myOrgs;
        if (roles.contains(StaffRoleEnum.TENANT_ADMIN.name())) {
            myOrgs = organizationService.listByTenant(ctx.getTenantId()).stream()
                    .map(org -> new OrgBrief(
                            org.getId(),
                            org.getOrgCode(),
                            org.getName(),
                            org.getOrgType(),
                            org.getStatus(),
                            false))
                    .toList();
        } else {
            List<StaffOrgBinding> bindings = identityService.staffOrgs(ctx.getStaffId());
            myOrgs = bindings.stream()
                    .map(b -> {
                        Organization org = organizationService.require(b.getOrgId());
                        return new OrgBrief(
                                org.getId(),
                                org.getOrgCode(),
                                org.getName(),
                                org.getOrgType(),
                                org.getStatus(),
                                b.getIsDefault() != null && b.getIsDefault() == 1);
                    })
                    .toList();
        }
        return ApiResult.ok(new StaffSessionResponse(
                profile.getId(),
                profile.getDisplayName(),
                profile.getTenantId(),
                ctx.getCurrentOrgId(),
                roles,
                myOrgs,
                tenantConfigService.branding(profile.getTenantId()),
                featureFlagService.list(profile.getTenantId())));
    }

    /** 当前账号的二次校验状态。 */
    @GetMapping("/session/mfa")
    public ApiResult<MfaService.MfaStatus> mfaStatus() {
        var ctx = SecurityUtils.requireContext();
        return ApiResult.ok(mfaService.status(MfaService.TYPE_STAFF, ctx.getAccountId()));
    }

    /** 生成 TOTP 密钥（待验证）；密钥只在此处下发一次。 */
    @PostMapping("/session/mfa/setup")
    public ApiResult<MfaService.MfaSetup> mfaSetup() {
        var ctx = SecurityUtils.requireContext();
        StaffProfile profile = identityService.requireStaff(ctx.getStaffId());
        return ApiResult.ok(mfaService.setup(
                MfaService.TYPE_STAFF,
                ctx.getAccountId(),
                profile.getDisplayName() == null ? ctx.getAccountId() : profile.getDisplayName()));
    }

    @PostMapping("/session/mfa/enable")
    public ApiResult<MfaService.MfaStatus> mfaEnable(@RequestBody @Validated MfaCodeRequest request) {
        var ctx = SecurityUtils.requireContext();
        mfaService.enable(MfaService.TYPE_STAFF, ctx.getAccountId(), request.code());
        auditService.record(
                PortalEnum.B.code(),
                ctx.getAccountId(),
                "STAFF",
                ctx.getTenantId(),
                AuditActionEnum.MFA_ENABLE.name(),
                "account_mfa",
                ctx.getAccountId(),
                null,
                null);
        return ApiResult.ok(mfaService.status(MfaService.TYPE_STAFF, ctx.getAccountId()));
    }

    @PostMapping("/session/mfa/disable")
    public ApiResult<MfaService.MfaStatus> mfaDisable(@RequestBody @Validated MfaCodeRequest request) {
        var ctx = SecurityUtils.requireContext();
        mfaService.disable(MfaService.TYPE_STAFF, ctx.getAccountId(), request.code());
        auditService.record(
                PortalEnum.B.code(),
                ctx.getAccountId(),
                "STAFF",
                ctx.getTenantId(),
                AuditActionEnum.MFA_DISABLE.name(),
                "account_mfa",
                ctx.getAccountId(),
                null,
                null);
        return ApiResult.ok(mfaService.status(MfaService.TYPE_STAFF, ctx.getAccountId()));
    }

    /** 租户管理员创建机构。 */
    @PostMapping("/orgs")
    public ApiResult<Organization> createOrg(@RequestBody @Validated CreateOrgRequest request) {
        String accountId = SecurityUtils.requireContext().getAccountId();
        String staffId = SecurityUtils.requireStaffId();
        return ApiResult.ok(tenantAdminService.createOrg(request.name(), request.orgType(), staffId, accountId));
    }

    /** 租户管理员更新机构信息 / 状态。 */
    @PutMapping("/orgs/{orgId}")
    public ApiResult<Organization> updateOrg(
            @PathVariable String orgId, @RequestBody @Validated UpdateOrgRequest request) {
        String accountId = SecurityUtils.requireContext().getAccountId();
        String staffId = SecurityUtils.requireStaffId();
        return ApiResult.ok(tenantAdminService.updateOrg(
                orgId, request.name(), request.orgType(), request.status(), staffId, accountId));
    }

    /** 列出当前用户可见机构（管理员看全租户，其他看 binding）。 */
    @GetMapping("/orgs")
    public ApiResult<List<Organization>> listOrgs() {
        return ApiResult.ok(tenantAdminService.listOrgsForCurrentUser());
    }

    /** 生成机构患者邀请码（权限：管理员任意机构；一线角色限已绑定机构）。 */
    @PostMapping("/orgs/{orgId}/invites")
    public ApiResult<OrgInviteCode> createInvite(
            @PathVariable String orgId, @RequestBody(required = false) InviteRequest request) {
        tenantAdminService.assertCanCreateInvite(orgId);
        String tenantId = SecurityUtils.requireTenantId();
        String staffId = SecurityUtils.requireStaffId();
        int days = request == null || request.validDays() == null ? 7 : request.validDays();
        return ApiResult.ok(organizationService.createInvite(tenantId, orgId, staffId, days));
    }

    /** 租户管理员注册本租户 Staff（可附角色与机构权限）。 */
    @PostMapping("/staff-accounts")
    public ApiResult<StaffRegisterResult> registerStaff(@RequestBody @Validated RegisterStaffRequest request) {
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(tenantAdminService.registerStaff(
                new RegisterStaffCommand(
                        request.username(),
                        request.password(),
                        request.displayName(),
                        request.mobile(),
                        request.title(),
                        request.roleCode(),
                        request.orgIds()),
                accountId));
    }

    /** 租户管理员分页查询本租户员工账号。 */
    @GetMapping("/staff-accounts")
    public ApiResult<PageResult<StaffAccountListItem>> listStaff(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) String orgId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(tenantAdminService.listStaff(
                new StaffListQuery(keyword, status, roleCode, orgId, page, pageSize)));
    }

    /** 租户管理员更新员工资料 / 状态 / 密码。 */
    @PutMapping("/staff/{staffId}")
    public ApiResult<StaffUpdateResult> updateStaff(
            @PathVariable String staffId, @RequestBody @Validated UpdateStaffRequest request) {
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(tenantAdminService.updateStaff(
                staffId,
                new UpdateStaffCommand(
                        request.displayName(),
                        request.mobile(),
                        request.title(),
                        request.status(),
                        request.password()),
                accountId));
    }

    /** 替换员工功能角色（本期 UI 单选健管师/医生；禁止授 TENANT_ADMIN）。 */
    @PutMapping("/staff/{staffId}/roles")
    public ApiResult<ReplaceRoleResult> replaceRole(
            @PathVariable String staffId, @RequestBody @Validated ReplaceRoleRequest request) {
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(tenantAdminService.replaceStaffRole(staffId, request.roleCode(), accountId));
    }

    /** 全量覆盖员工可访问机构列表（staff_org_binding）。 */
    @PutMapping("/staff/{staffId}/orgs")
    public ApiResult<ReplaceOrgsResult> replaceOrgs(
            @PathVariable String staffId, @RequestBody @Validated ReplaceOrgsRequest request) {
        String accountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(tenantAdminService.replaceStaffOrgs(staffId, request.orgIds(), accountId));
    }

    /** 列出当前机构有效患者会员关系（较简；工作台列表请用 /org-patients）。 */
    @GetMapping("/patients")
    public ApiResult<List<PatientOrgMembership>> listPatients() {
        String orgId = SecurityUtils.requireCurrentOrgId();
        return ApiResult.ok(membershipService.listActiveByOrg(orgId));
    }

    /** 查询患者某指标在时间范围内的体征记录。 */
    @GetMapping("/patients/{patientId}/vitals")
    public ApiResult<List<VitalRecord>> patientVitals(
            @PathVariable String patientId,
            @RequestParam String metricType,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {
        String tenantId = SecurityUtils.requireTenantId();
        PeopleProfile profile = identityService.requirePeople(patientId);
        if (profile.getTenantId() == null || !profile.getTenantId().equals(tenantId)) {
            throw new BusinessException("患者不属于当前租户");
        }
        if (!membershipService.hasActiveMembershipInTenant(patientId, tenantId)) {
            throw new BusinessException("患者在本租户无有效机构关系");
        }
        return ApiResult.ok(vitalService.list(tenantId, patientId, metricType, from, to));
    }

    /** @param mfaCode 启用二次校验的账号必填；未启用时忽略 */
    public record LoginRequest(@NotBlank String username, @NotBlank String password, String mfaCode) {
    }

    public record SwitchOrgRequest(@NotNull String orgId) {
    }

    public record MfaCodeRequest(@NotBlank String code) {
    }

    public record CreateOrgRequest(@NotBlank String name, String orgType) {
    }

    public record UpdateOrgRequest(@NotBlank String name, String orgType, String status) {
    }

    public record InviteRequest(Integer validDays) {
    }

    public record RegisterStaffRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String displayName,
            String mobile,
            String title,
            String roleCode,
            List<String> orgIds) {
    }

    public record UpdateStaffRequest(
            @NotBlank String displayName,
            String mobile,
            String title,
            String status,
            @Size(min = 8) String password) {
    }

    public record ReplaceRoleRequest(String roleCode) {
    }

    public record ReplaceOrgsRequest(@NotNull List<String> orgIds) {
    }

    public record StaffTokenResponse(
            String accessToken,
            String accountId,
            String staffId,
            String tenantId,
            String currentOrgId,
            Set<String> roles) {
    }

    public record OrgBrief(
            String id, String orgCode, String name, String orgType, String status, boolean isDefault) {
    }

    /**
     * @param branding 白标配置；未开通白标时各字段为空
     * @param flags 功能开关，前端据此隐藏未开通入口
     */
    public record StaffSessionResponse(
            String staffId,
            String displayName,
            String tenantId,
            String currentOrgId,
            Set<String> roles,
            List<OrgBrief> orgs,
            BrandingView branding,
            List<FlagView> flags) {
    }
}
