package com.healix.core.tenantadmin.service;

import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.govern.service.QuotaService;
import com.healix.core.identity.enums.EnableStatusEnum;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
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
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.org.service.OrganizationService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
public class TenantAdminService {

    private static final int MAX_PAGE_SIZE = 100;

    private final OrganizationService organizationService;
    private final OrganizationMapper organizationMapper;
    private final StaffAccountMapper staffAccountMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffOrgBindingMapper staffOrgBindingMapper;
    private final StaffRoleBindingMapper staffRoleBindingMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final QuotaService quotaService;

    @Transactional
    public Organization createOrg(String name, String orgType, String actorStaffId, String actorAccountId) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        Organization org = organizationService.create(tenantId, name, orgType);
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.ORG_CREATE.name(),
                "organization",
                String.valueOf(org.getId()),
                null,
                AuditDetails.of("name", org.getName(), "staffId", actorStaffId));
        return org;
    }

    @Transactional
    public Organization updateOrg(
            String orgId, String name, String orgType, String status, String actorStaffId, String actorAccountId) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        Organization existing = organizationService.require(orgId);
        if (!tenantId.equals(existing.getTenantId())) {
            throw new BusinessException("机构不属于当前租户");
        }
        Organization org = organizationService.update(orgId, name, orgType, status);
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.ORG_UPDATE.name(),
                "organization",
                String.valueOf(org.getId()),
                null,
                AuditDetails.of(
                        "name", org.getName(),
                        "orgType", org.getOrgType(),
                        "status", org.getStatus(),
                        "staffId", actorStaffId));
        return org;
    }

    public List<Organization> listOrgsForCurrentUser() {
        String tenantId = requireTenantId();
        if (isTenantAdmin()) {
            return organizationService.listByTenant(tenantId);
        }
        String staffId = requireStaffId();
        List<StaffOrgBinding> bindings = staffOrgBindingMapper.listByStaff(staffId);
        List<Organization> result = new ArrayList<>();
        for (StaffOrgBinding binding : bindings) {
            Organization org = organizationMapper.findById(binding.getOrgId());
            if (org != null && tenantId.equals(org.getTenantId())) {
                result.add(org);
            }
        }
        return result;
    }

    public void assertCanCreateInvite(String orgId) {
        String tenantId = requireTenantId();
        Organization org = organizationService.require(orgId);
        if (!tenantId.equals(org.getTenantId())) {
            throw new BusinessException("机构不属于当前租户");
        }
        if (isTenantAdmin()) {
            return;
        }
        Set<String> roles = currentRoles();
        if (!roles.contains(StaffRoleEnum.CARE_MANAGER.name()) && !roles.contains(StaffRoleEnum.DOCTOR.name())) {
            throw new BusinessException(403, "无权创建邀请码");
        }
        if (staffOrgBindingMapper.countBinding(requireStaffId(), orgId) <= 0) {
            throw new BusinessException(403, "员工未绑定该机构");
        }
    }

    @Transactional
    public StaffRegisterResult registerStaff(RegisterStaffCommand cmd, String actorAccountId) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        if (staffAccountMapper.findByUsername(cmd.username()) != null) {
            throw new BusinessException(409, "员工用户名已存在");
        }
        quotaService.assertAvailable(tenantId, QuotaKeyEnum.STAFF_TOTAL, 1);

        StaffAccount account = new StaffAccount();
        account.setUsername(cmd.username());
        account.setPasswordHash(passwordEncoder.encode(cmd.password()));
        account.setStatus(EnableStatusEnum.ACTIVE.name());
        EntityMeta.onCreate(account);
        staffAccountMapper.insert(account);

        StaffProfile profile = new StaffProfile();
        profile.setTenantId(tenantId);
        profile.setAccountId(account.getId());
        profile.setDisplayName(cmd.displayName());
        profile.setMobile(cmd.mobile());
        profile.setTitle(cmd.title());
        EntityMeta.onCreate(profile);
        staffProfileMapper.insert(profile);

        String roleCode = blankToNull(cmd.roleCode());
        if (roleCode != null) {
            assertAssignableRole(roleCode);
            insertRole(tenantId, profile.getId(), roleCode);
        }

        List<String> orgIds = normalizeOrgIds(tenantId, cmd.orgIds());
        replaceBindings(tenantId, profile.getId(), orgIds);

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.STAFF_ACCOUNT_CREATE.name(),
                "staff_account",
                String.valueOf(account.getId()),
                null,
                AuditDetails.of(
                        "staffId", profile.getId(),
                        "username", cmd.username(),
                        "roleCode", roleCode,
                        "orgCount", orgIds.size()));

        return new StaffRegisterResult(profile.getId(), account.getId(), tenantId, roleCode, orgIds);
    }

    @Transactional
    public StaffUpdateResult updateStaff(String staffId, UpdateStaffCommand cmd, String actorAccountId) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        StaffProfile profile = requireStaffInTenant(staffId, tenantId);
        StaffAccount account = staffAccountMapper.findById(profile.getAccountId());
        if (account == null) {
            throw new BusinessException(404, "员工账号不存在");
        }

        if (!StringUtils.hasText(cmd.displayName())) {
            throw new BusinessException(400, "请填写姓名");
        }

        profile.setDisplayName(cmd.displayName().trim());
        profile.setMobile(blankToNull(cmd.mobile()));
        profile.setTitle(blankToNull(cmd.title()));
        EntityMeta.onUpdate(profile);
        staffProfileMapper.updateProfile(profile);

        String status = blankToNull(cmd.status());
        if (status != null) {
            account.setStatus(EnableStatusEnum.valueOf(status).name());
        }
        String newPassword = blankToNull(cmd.password());
        if (newPassword != null) {
            if (newPassword.length() < 8) {
                throw new BusinessException(400, "密码至少 8 位");
            }
            account.setPasswordHash(passwordEncoder.encode(newPassword));
        } else {
            account.setPasswordHash(null); // mapper skips when blank/null
        }
        EntityMeta.onUpdate(account);
        staffAccountMapper.updateStatusAndPassword(account);

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.STAFF_ACCOUNT_UPDATE.name(),
                "staff_account",
                String.valueOf(account.getId()),
                null,
                AuditDetails.of(
                        "staffId", staffId,
                        "displayName", profile.getDisplayName(),
                        "status", account.getStatus(),
                        "passwordReset", newPassword != null));

        return new StaffUpdateResult(
                staffId, account.getId(), profile.getDisplayName(), profile.getMobile(), profile.getTitle(), account.getStatus());
    }

    public PageResult<StaffAccountListItem> listStaff(StaffListQuery query) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        int page = query.page() == null || query.page() < 1 ? 1 : query.page();
        int pageSize = normalizePageSize(query.pageSize());
        int offset = (page - 1) * pageSize;

        String keyword = blankToNull(query.keyword());
        String status = blankToNull(query.status());
        String roleCode = blankToNull(query.roleCode());
        String orgId = query.orgId();

        long total = staffProfileMapper.countByTenant(tenantId, keyword, status, roleCode, orgId);
        if (total == 0) {
            return new PageResult<>(0, List.of());
        }

        List<StaffProfileListRow> rows =
                staffProfileMapper.listByTenant(tenantId, keyword, status, roleCode, orgId, offset, pageSize);
        List<String> staffIds = rows.stream().map(StaffProfileListRow::getStaffId).toList();
        Map<String, List<String>> rolesByStaff = loadRolesByStaff(staffIds);
        Map<String, List<String>> orgsByStaff = loadOrgsByStaff(staffIds);
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
            item.setOrgIds(orgsByStaff.getOrDefault(row.getStaffId(), List.of()));
            item.setDefaultOrgId(defaultOrgByStaff.get(row.getStaffId()));
            item.setCreatedAt(row.getCreatedAt());
            items.add(item);
        }
        return new PageResult<>(total, items);
    }

    @Transactional
    public ReplaceRoleResult replaceStaffRole(String staffId, String roleCode, String actorAccountId) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        StaffProfile profile = requireStaffInTenant(staffId, tenantId);

        LocalDateTime now = LocalDateTime.now();
        staffRoleBindingMapper.softDeleteAssignableByStaff(staffId, now, now);

        String normalized = blankToNull(roleCode);
        if (normalized != null) {
            assertAssignableRole(normalized);
            insertRole(tenantId, profile.getId(), normalized);
        }

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.STAFF_ROLE_GRANT.name(),
                "staff_role",
                String.valueOf(staffId),
                null,
                AuditDetails.of("staffId", staffId, "roleCode", normalized));

        return new ReplaceRoleResult(staffId, normalized);
    }

    @Transactional
    public ReplaceOrgsResult replaceStaffOrgs(String staffId, List<String> orgIds, String actorAccountId) {
        requireTenantAdmin();
        String tenantId = requireTenantId();
        requireStaffInTenant(staffId, tenantId);

        List<String> normalized = normalizeOrgIds(tenantId, orgIds);
        replaceBindings(tenantId, staffId, normalized);

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.STAFF_ORG_BIND_REPLACE.name(),
                "staff_org",
                String.valueOf(staffId),
                null,
                AuditDetails.of("staffId", staffId, "orgIds", normalized));

        return new ReplaceOrgsResult(staffId, normalized);
    }

    private void replaceBindings(String tenantId, String staffId, List<String> orgIds) {
        LocalDateTime now = LocalDateTime.now();
        staffOrgBindingMapper.softDeleteByStaff(staffId, now, now);
        boolean first = true;
        for (String orgId : orgIds) {
            StaffOrgBinding binding = new StaffOrgBinding();
            binding.setTenantId(tenantId);
            binding.setStaffId(staffId);
            binding.setOrgId(orgId);
            binding.setIsDefault(first ? 1 : 0);
            EntityMeta.onCreate(binding);
            staffOrgBindingMapper.insert(binding);
            first = false;
        }
    }

    private void insertRole(String tenantId, String staffId, String roleCode) {
        StaffRoleBinding role = new StaffRoleBinding();
        role.setTenantId(tenantId);
        role.setStaffId(staffId);
        role.setRoleCode(roleCode);
        role.setOrgId(null);
        EntityMeta.onCreate(role);
        staffRoleBindingMapper.insert(role);
    }

    private List<String> normalizeOrgIds(String tenantId, List<String> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String orgId : orgIds) {
            if (orgId == null) {
                continue;
            }
            Organization org = organizationMapper.findById(orgId);
            if (org == null || !tenantId.equals(org.getTenantId())) {
                throw new BusinessException(400, "机构不属于该租户：" + orgId);
            }
            unique.add(orgId);
        }
        return List.copyOf(unique);
    }

    private StaffProfile requireStaffInTenant(String staffId, String tenantId) {
        StaffProfile profile = staffProfileMapper.findById(staffId);
        if (profile == null || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(404, "租户内未找到该员工");
        }
        return profile;
    }

    private static void assertAssignableRole(String roleCode) {
        if (!StaffRoleEnum.isAssignableCode(roleCode)) {
            throw new BusinessException(403, "不可分配该角色：" + roleCode);
        }
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

    private Map<String, List<String>> loadOrgsByStaff(List<String> staffIds) {
        if (staffIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> map = new HashMap<>();
        for (StaffOrgBinding binding : staffOrgBindingMapper.listByStaffIds(staffIds)) {
            map.computeIfAbsent(binding.getStaffId(), k -> new ArrayList<>()).add(binding.getOrgId());
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

    private static void requireTenantAdmin() {
        if (!isTenantAdmin()) {
            throw new BusinessException(403, "需要租户管理员权限");
        }
    }

    private static boolean isTenantAdmin() {
        return currentRoles().contains(StaffRoleEnum.TENANT_ADMIN.name());
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return ctx.getRoles();
    }

    private static String requireTenantId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new BusinessException(401, "缺少租户上下文");
        }
        return ctx.getTenantId();
    }

    private static String requireStaffId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            throw new BusinessException(401, "缺少员工上下文");
        }
        return ctx.getStaffId();
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize < 1) {
            return 20;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record RegisterStaffCommand(
            String username,
            String password,
            String displayName,
            String mobile,
            String title,
            String roleCode,
            List<String> orgIds) {
    }

    public record StaffListQuery(
            String keyword, String status, String roleCode, String orgId, Integer page, Integer pageSize) {
    }

    public record StaffRegisterResult(
            String staffId, String accountId, String tenantId, String roleCode, List<String> orgIds) {
    }

    public record UpdateStaffCommand(
            String displayName, String mobile, String title, String status, String password) {
    }

    public record StaffUpdateResult(
            String staffId,
            String accountId,
            String displayName,
            String mobile,
            String title,
            String status) {
    }

    public record ReplaceRoleResult(String staffId, String roleCode) {
    }

    public record ReplaceOrgsResult(String staffId, List<String> orgIds) {
    }
}
