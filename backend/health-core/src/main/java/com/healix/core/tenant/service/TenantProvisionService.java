package com.healix.core.tenant.service;

import com.healix.core.ops.service.OpsTenantAdminService;
import com.healix.core.ops.service.OpsTenantAdminService.InitialAdminCommand;
import com.healix.core.ops.service.OpsTenantAdminService.ProvisionResult;
import com.healix.core.ops.service.OpsTenantAdminService.ProvisionTenantCommand;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** @deprecated 请直接使用 {@link OpsTenantAdminService#provisionTenant} */
@Service
@RequiredArgsConstructor
public class TenantProvisionService {

    private final OpsTenantAdminService opsTenantAdminService;

    public ProvisionResult provision(
            String tenantCode,
            String tenantName,
            String adminUsername,
            String adminPassword,
            String adminDisplayName,
            String opsAccountId) {
        InitialAdminCommand admin = new InitialAdminCommand(
                adminUsername, adminPassword, adminDisplayName, null, null, true, null);
        return opsTenantAdminService.provisionTenant(
                new ProvisionTenantCommand(tenantCode, tenantName, admin), opsAccountId);
    }

    /** @deprecated 使用 {@link ProvisionResult} */
    @Deprecated
    public record ProvisionResultLegacy(String tenantId, String orgId, String staffId, String staffAccountId) {
        public static ProvisionResultLegacy from(ProvisionResult result) {
            return new ProvisionResultLegacy(
                    result.tenantId(), null, result.staffId(), result.accountId());
        }
    }
}
