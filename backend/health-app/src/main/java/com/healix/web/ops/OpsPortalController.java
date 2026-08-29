package com.healix.web.ops;

import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.common.util.AuditDetails;
import com.healix.common.result.ApiResult;
import com.healix.common.result.PageResult;
import com.healix.core.audit.service.AuditService;
import com.healix.core.identity.domain.OpsAccount;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.ops.dto.StaffAccountListItem;
import com.healix.core.ops.dto.TenantListItem;
import com.healix.core.ops.service.OpsTenantAdminService;
import com.healix.core.ops.service.OpsTenantAdminService.GrantTenantAdminResult;
import com.healix.core.ops.service.OpsTenantAdminService.InitialAdminCommand;
import com.healix.core.ops.service.OpsTenantAdminService.ProvisionResult;
import com.healix.core.ops.service.OpsTenantAdminService.ProvisionTenantCommand;
import com.healix.core.ops.service.OpsTenantAdminService.RegisterStaffCommand;
import com.healix.core.ops.service.OpsTenantAdminService.StaffListQuery;
import com.healix.core.ops.service.OpsTenantAdminService.StaffRegisterResult;
import com.healix.core.ops.service.OpsTenantAdminService.TenantListQuery;
import com.healix.security.JwtTokenProvider;
import com.healix.security.SecurityUtils;
import com.healix.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/ops/v1")
@RequiredArgsConstructor
public class OpsPortalController {

    private final IdentityService identityService;
    private final OpsTenantAdminService opsTenantAdminService;
    private final AuditService auditService;
    private final JwtTokenProvider jwtTokenProvider;

    @PostMapping("/auth/login")
    public ApiResult<OpsTokenResponse> login(@RequestBody @Validated LoginRequest request) {
        OpsAccount account = identityService.requireOpsByUsername(request.username());
        if (!identityService.matches(request.password(), account.getPasswordHash())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        String token = jwtTokenProvider.createOpsToken(account.getId(), account.getRoleCode());
        return ApiResult.ok(new OpsTokenResponse(token, account.getId(), account.getRoleCode()));
    }

    @PostMapping("/tenants")
    public ApiResult<ProvisionResult> createTenant(@RequestBody @Validated CreateTenantRequest request) {
        String opsAccountId = SecurityUtils.requireContext().getAccountId();
        InitialAdminCommand initialAdmin = null;
        if (request.initialAdmin() != null) {
            InitialAdminRequest admin = request.initialAdmin();
            initialAdmin = new InitialAdminCommand(
                    admin.username(),
                    admin.password(),
                    admin.displayName(),
                    admin.mobile(),
                    admin.title(),
                    admin.grantTenantAdmin(),
                    admin.bindDefaultOrgId());
        }
        return ApiResult.ok(opsTenantAdminService.provisionTenant(
                new ProvisionTenantCommand(request.code(), request.name(), initialAdmin), opsAccountId));
    }

    @GetMapping("/tenants")
    public ApiResult<PageResult<TenantListItem>> listTenants(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(opsTenantAdminService.listTenants(new TenantListQuery(keyword, page, pageSize)));
    }

    @PostMapping("/tenants/{tenantId}/staff-accounts")
    public ApiResult<StaffRegisterResult> registerStaff(
            @PathVariable String tenantId, @RequestBody @Validated RegisterStaffRequest request) {
        String opsAccountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(opsTenantAdminService.registerStaff(
                tenantId,
                new RegisterStaffCommand(
                        request.username(),
                        request.password(),
                        request.displayName(),
                        request.mobile(),
                        request.title(),
                        request.grantTenantAdmin(),
                        request.bindDefaultOrgId()),
                opsAccountId));
    }

    @PostMapping("/tenants/{tenantId}/staff/{staffId}/roles/tenant-admin")
    public ApiResult<GrantTenantAdminResult> grantTenantAdmin(
            @PathVariable String tenantId, @PathVariable String staffId) {
        String opsAccountId = SecurityUtils.requireContext().getAccountId();
        return ApiResult.ok(opsTenantAdminService.grantTenantAdmin(tenantId, staffId, opsAccountId));
    }

    @GetMapping("/tenants/{tenantId}/staff-accounts")
    public ApiResult<PageResult<StaffAccountListItem>> listStaff(
            @PathVariable String tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResult.ok(opsTenantAdminService.listStaff(
                tenantId, new StaffListQuery(keyword, status, roleCode, page, pageSize)));
    }

    @PostMapping("/tenants/{tenantId}/staff-accounts/audit-view")
    public ApiResult<Void> auditStaffTabView(@PathVariable String tenantId) {
        String opsAccountId = SecurityUtils.requireContext().getAccountId();
        opsTenantAdminService.auditStaffTabView(tenantId, opsAccountId);
        return ApiResult.ok();
    }

    @GetMapping("/patients/search")
    public ApiResult<List<PeopleProfile>> searchPatients(
            @RequestParam String tenantId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        String opsAccountId = SecurityUtils.requireContext().getAccountId();
        List<PeopleProfile> list = identityService.searchPeople(tenantId, keyword, Math.min(limit, 100));
        auditService.record(
                PortalEnum.OPS.code(),
                opsAccountId,
                "OPS",
                tenantId,
                AuditActionEnum.OPS_PHI_VIEW.name(),
                "patient_search",
                keyword,
                null,
                AuditDetails.of("count", list.size()));
        return ApiResult.ok(list);
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record CreateTenantRequest(
            @NotBlank String code, @NotBlank String name, @Valid InitialAdminRequest initialAdmin) {
    }

    public record InitialAdminRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String displayName,
            String mobile,
            String title,
            Boolean grantTenantAdmin,
            String bindDefaultOrgId) {
    }

    public record RegisterStaffRequest(
            @NotBlank String username,
            @NotBlank @Size(min = 8) String password,
            @NotBlank String displayName,
            String mobile,
            String title,
            Boolean grantTenantAdmin,
            String bindDefaultOrgId) {
    }

    public record OpsTokenResponse(String accessToken, String accountId, String roleCode) {
    }
}
