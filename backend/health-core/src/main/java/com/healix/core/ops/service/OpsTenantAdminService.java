package com.healix.core.ops.service;

import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.identity.enums.EnableStatusEnum;
import com.healix.core.identity.enums.OpsRoleEnum;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.tenant.enums.TenantStatusEnum;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.util.AuditDetails;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.core.audit.service.AuditService;
import com.healix.core.identity.domain.StaffAccount;
import com.healix.core.identity.domain.StaffOrgBinding;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.domain.StaffRoleBinding;
import com.healix.core.identity.mapper.StaffAccountMapper;
import com.healix.core.identity.mapper.StaffOrgBindingMapper;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.identity.mapper.StaffRoleBindingMapper;
import com.healix.core.ops.dto.StaffAccountListItem;
import com.healix.core.ops.dto.StaffProfileListRow;
import com.healix.core.ops.dto.TenantListItem;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsTenantAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final StaffAccountMapper staffAccountMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffOrgBindingMapper staffOrgBindingMapper;
    private final StaffRoleBindingMapper staffRoleBindingMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    @Transactional
    public ProvisionResult provisionTenant(ProvisionTenantCommand cmd, String opsAccountId) {
        requireSuperAdmin();
        if (tenantMapper.findByCode(cmd.code()) != null) {
            throw new BusinessException(409, "租户编码已存在");
        }

        Tenant tenant = new Tenant();
        tenant.setCode(cmd.code());
        tenant.setName(cmd.name());
        tenant.setStatus(TenantStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(tenant);
        tenantMapper.insert(tenant);

        String staffId = null;
        String accountId = null;
        boolean grantedTenantAdmin = false;

        InitialAdminCommand admin = cmd.initialAdmin();
        if (admin != null) {
            if (staffAccountMapper.findByUsername(admin.username()) != null) {
                throw new BusinessException(409, "员工用户名已存在");
            }
            StaffCreation created = createStaff(
                    tenant.getId(),
                    admin.username(),
                    admin.password(),
                    admin.displayName(),
                    admin.mobile(),
                    admin.title(),
                    admin.bindDefaultOrgId());
            staffId = created.staffId();
            accountId = created.accountId();

            boolean grant = admin.grantTenantAdmin() == null || admin.grantTenantAdmin();
            if (grant) {
                insertTenantAdminRole(tenant.getId(), staffId);
                grantedTenantAdmin = true;
            }
        }

        boolean hasTenantAdmin = grantedTenantAdmin || tenantMapper.hasTenantAdmin(tenant.getId());
        auditService.record(
                PortalEnum.OPS.code(),
                opsAccountId,
                "OPS",
                tenant.getId(),
                AuditActionEnum.TENANT_CREATE.name(),
                "tenant",
                String.valueOf(tenant.getId()),
                null,
                AuditDetails.of("hasInitialAdmin", admin != null, "hasTenantAdmin", hasTenantAdmin));

        return new ProvisionResult(tenant.getId(), staffId, accountId, hasTenantAdmin);
    }

    @Transactional
    public StaffRegisterResult registerStaff(String tenantId, RegisterStaffCommand cmd, String opsAccountId) {
        requireSuperAdmin();
        Tenant tenant = requireTenant(tenantId);

        if (staffAccountMapper.findByUsername(cmd.username()) != null) {
            throw new BusinessException(409, "员工用户名已存在");
        }

        StaffCreation created = createStaff(
                tenant.getId(),
                cmd.username(),
                cmd.password(),
                cmd.displayName(),
                cmd.mobile(),
                cmd.title(),
                cmd.bindDefaultOrgId());

        boolean granted = Boolean.TRUE.equals(cmd.grantTenantAdmin());
        if (granted) {
            insertTenantAdminRole(tenant.getId(), created.staffId());
        }

        auditService.record(
                PortalEnum.OPS.code(),
                opsAccountId,
                "OPS",
                tenant.getId(),
                AuditActionEnum.STAFF_ACCOUNT_CREATE.name(),
                "staff_account",
                String.valueOf(created.accountId()),
                null,
                AuditDetails.of(
                        "staffId", created.staffId(),
                        "username", cmd.username(),
                        "grantedTenantAdmin", granted));

        return new StaffRegisterResult(
                created.staffId(), created.accountId(), tenant.getId(), granted);
    }

    @Transactional
    public GrantTenantAdminResult grantTenantAdmin(String tenantId, String staffId, String opsAccountId) {
        requireSuperAdmin();
        requireTenant(tenantId);
        StaffProfile profile = staffProfileMapper.findById(staffId);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(404, "租户内未找到该员工");
        }

        if (staffRoleBindingMapper.findTenantAdminByStaff(staffId) == null) {
            insertTenantAdminRole(tenantId, staffId);
            auditService.record(
                    PortalEnum.OPS.code(),
                    opsAccountId,
                    "OPS",
                    tenantId,
                    AuditActionEnum.STAFF_ROLE_GRANT.name(),
                    "staff_role",
                    String.valueOf(staffId),
                    null,
                    AuditDetails.of("staffId", staffId, "roleCode", StaffRoleEnum.TENANT_ADMIN.name()));
        }

        return new GrantTenantAdminResult(staffId, StaffRoleEnum.TENANT_ADMIN.name());
    }

    public PageResult<StaffAccountListItem> listStaff(String tenantId, StaffListQuery query) {
        requireOpsReader();
        requireTenant(tenantId);

        int page = query.page() == null || query.page() < 1 ? 1 : query.page();
        int pageSize = normalizePageSize(query.pageSize());
        int offset = (page - 1) * pageSize;

        String keyword = blankToNull(query.keyword());
        String status = blankToNull(query.status());
        String roleCode = blankToNull(query.roleCode());

        long total = staffProfileMapper.countByTenant(tenantId, keyword, status, roleCode, null);
        if (total == 0) {
            return new PageResult<>(0, List.of());
        }

        List<StaffProfileListRow> rows =
                staffProfileMapper.listByTenant(tenantId, keyword, status, roleCode, null, offset, pageSize);
        List<String> staffIds = rows.stream().map(StaffProfileListRow::getStaffId).toList();

        Map<String, List<String>> rolesByStaff = loadRolesByStaff(staffIds);
        Map<String, String> defaultOrgByStaff = loadDefaultOrgByStaff(staffIds);

        List<StaffAccountListItem> items = new ArrayList<>(rows.size());
        for (StaffProfileListRow row : rows) {
            StaffAccountListItem item = new StaffAccountListItem();
            item.setStaffId(row.getStaffId());
            item.setAccountId(row.getAccountId());
            item.setUsername(row.getUsername());
            item.setDisplayName(row.getDisplayName());
            item.setMobile(row.getMobile());
            item.setTitle(row.getTitle());
            item.setStatus(row.getStatus());
            item.setRoles(rolesByStaff.getOrDefault(row.getStaffId(), List.of()));
            item.setDefaultOrgId(defaultOrgByStaff.get(row.getStaffId()));
            item.setCreatedAt(row.getCreatedAt());
            items.add(item);
        }
        return new PageResult<>(total, items);
    }

    public PageResult<TenantListItem> listTenants(TenantListQuery query) {
        requireOpsReader();

        int page = query.page() == null || query.page() < 1 ? 1 : query.page();
        int pageSize = normalizePageSize(query.pageSize());
        int offset = (page - 1) * pageSize;
        String keyword = blankToNull(query.keyword());

        long total = tenantMapper.count(keyword);
        if (total == 0) {
            return new PageResult<>(0, List.of());
        }
        return new PageResult<>(total, tenantMapper.list(keyword, offset, pageSize));
    }

    public void auditStaffTabView(String tenantId, String opsAccountId) {
        requireOpsReader();
        requireTenant(tenantId);
        auditService.record(
                PortalEnum.OPS.code(),
                opsAccountId,
                "OPS",
                tenantId,
                AuditActionEnum.TENANT_STAFF_TAB_VIEW.name(),
                "tenant",
                String.valueOf(tenantId),
                null,
                null);
    }

    private StaffCreation createStaff(
            String tenantId,
            String username,
            String password,
            String displayName,
            String mobile,
            String title,
            String bindDefaultOrgId) {
        StaffAccount account = new StaffAccount();
        account.setUsername(username);
        account.setPasswordHash(passwordEncoder.encode(password));
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        staffAccountMapper.insert(account);

        StaffProfile profile = new StaffProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(account.getId());
        profile.setDisplayName(StringUtils.hasText(displayName) ? displayName : username);
        profile.setMobile(mobile);
        profile.setTitle(title);
        EntityMeta.onCreate(profile);
        staffProfileMapper.insert(profile);

        if (bindDefaultOrgId != null) {
            bindStaffToOrg(tenantId, profile.getId(), bindDefaultOrgId);
        }

        return new StaffCreation(profile.getId(), account.getId());
    }

    private void bindStaffToOrg(String tenantId, String staffId, String orgId) {
        Organization org = organizationMapper.findById(orgId);
        if (org == null || !tenantId.equals(org.getTenantId())) {
            throw new BusinessException(400, "机构不属于该租户");
        }
        if (staffOrgBindingMapper.countBinding(staffId, orgId) > 0) {
            return;
        }
        StaffOrgBinding binding = new StaffOrgBinding();
        binding.setTenantId(tenantId);
        binding.setStaffId(staffId);
        binding.setOrgId(orgId);
        binding.setIsDefault(1);
        EntityMeta.onCreate(binding);
        staffOrgBindingMapper.insert(binding);
    }

    private void insertTenantAdminRole(String tenantId, String staffId) {
        StaffRoleBinding role = new StaffRoleBinding();
        role.setTenantId(tenantId);
        role.setStaffId(staffId);
        role.setRoleCode(StaffRoleEnum.TENANT_ADMIN.name());
        role.setOrgId(null);
        EntityMeta.onCreate(role);
        staffRoleBindingMapper.insert(role);
    }

    private Tenant requireTenant(String tenantId) {
        Tenant tenant = tenantMapper.findById(tenantId);
        if (tenant == null) {
            throw new BusinessException(404, "租户不存在");
        }
        return tenant;
    }

    private Map<String, List<String>> loadRolesByStaff(List<String> staffIds) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> map = new HashMap<>();
        for (StaffRoleBinding binding : staffRoleBindingMapper.listByStaffIds(staffIds)) {
            map.computeIfAbsent(binding.getStaffId(), k -> new ArrayList<>()).add(binding.getRoleCode());
        }
        return map;
    }

    private Map<String, String> loadDefaultOrgByStaff(List<String> staffIds) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (StaffOrgBinding binding : staffOrgBindingMapper.listDefaultsByStaffIds(staffIds)) {
            map.put(binding.getStaffId(), binding.getOrgId());
        }
        return map;
    }

    private static void requireSuperAdmin() {
        if (!currentRoles().contains(OpsRoleEnum.SUPER_ADMIN.name())) {
            throw new BusinessException(403, "需要超级管理员权限");
        }
    }

    private static void requireOpsReader() {
        Set<String> roles = currentRoles();
        if (!roles.contains(OpsRoleEnum.SUPER_ADMIN.name()) && !roles.contains(OpsRoleEnum.OPERATOR.name())) {
            throw new BusinessException(403, "无权查看运营数据");
        }
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return ctx.getRoles();
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    public record InitialAdminCommand(
            String username,
            String password,
            String displayName,
            String mobile,
            String title,
            Boolean grantTenantAdmin,
            String bindDefaultOrgId) {
    }

    public record ProvisionTenantCommand(String code, String name, InitialAdminCommand initialAdmin) {
    }

    public record RegisterStaffCommand(
            String username,
            String password,
            String displayName,
            String mobile,
            String title,
            Boolean grantTenantAdmin,
            String bindDefaultOrgId) {
    }

    public record StaffListQuery(String keyword, String status, String roleCode, Integer page, Integer pageSize) {
    }

    public record TenantListQuery(String keyword, Integer page, Integer pageSize) {
    }

    public record ProvisionResult(String tenantId, String staffId, String accountId, boolean hasTenantAdmin) {
    }

    public record StaffRegisterResult(
            String staffId, String accountId, String tenantId, boolean grantedTenantAdmin) {
    }

    public record GrantTenantAdminResult(String staffId, String roleCode) {
    }

    private record StaffCreation(String staffId, String accountId) {
    }
}
