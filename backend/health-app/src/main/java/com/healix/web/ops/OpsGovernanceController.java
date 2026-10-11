package com.healix.web.ops;

import com.healix.common.result.ApiResult;
import com.healix.common.util.AuditDetails;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.govern.domain.TenantConfig;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.govern.service.FeatureFlagService.FlagView;
import com.healix.core.govern.service.QuotaService;
import com.healix.core.govern.service.QuotaService.QuotaView;
import com.healix.core.govern.service.TenantConfigService;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.security.SecurityUtils;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ops 租户治理：配额上限、功能开关、白标配置。 */
@Validated
@RestController
@RequestMapping("/api/ops/v1/tenants/{tenantId}/governance")
@RequiredArgsConstructor
public class OpsGovernanceController {

    private final QuotaService quotaService;
    private final FeatureFlagService featureFlagService;
    private final TenantConfigService tenantConfigService;
    private final AuditService auditService;

    @GetMapping
    public ApiResult<GovernanceView> get(@PathVariable String tenantId) {
        return ApiResult.ok(new GovernanceView(
                quotaService.list(tenantId),
                featureFlagService.list(tenantId),
                tenantConfigService.raw(tenantId)));
    }

    @PutMapping("/quotas")
    public ApiResult<List<QuotaView>> setQuota(
            @PathVariable String tenantId, @RequestBody @Validated QuotaRequest request) {
        quotaService.setLimit(tenantId, request.quotaKey(), request.limitValue());
        audit(
                tenantId,
                AuditActionEnum.TENANT_QUOTA_UPDATE,
                request.quotaKey(),
                AuditDetails.of("limitValue", request.limitValue()));
        return ApiResult.ok(quotaService.list(tenantId));
    }

    @PutMapping("/flags")
    public ApiResult<List<FlagView>> setFlag(
            @PathVariable String tenantId, @RequestBody @Validated FlagRequest request) {
        featureFlagService.set(tenantId, request.flagKey(), request.enabled(), request.configJson());
        audit(
                tenantId,
                AuditActionEnum.TENANT_FEATURE_FLAG_UPDATE,
                request.flagKey(),
                AuditDetails.of("enabled", request.enabled()));
        return ApiResult.ok(featureFlagService.list(tenantId));
    }

    @PutMapping("/config")
    public ApiResult<TenantConfig> setConfig(
            @PathVariable String tenantId, @RequestBody ConfigRequest request) {
        TenantConfig input = new TenantConfig();
        input.setAppName(request.appName());
        input.setLogoUrl(request.logoUrl());
        input.setPrimaryColor(request.primaryColor());
        input.setLoginSlogan(request.loginSlogan());
        input.setSupportPhone(request.supportPhone());
        tenantConfigService.save(tenantId, input);
        audit(
                tenantId,
                AuditActionEnum.TENANT_CONFIG_UPDATE,
                "tenant_config",
                AuditDetails.of("appName", request.appName()));
        return ApiResult.ok(tenantConfigService.raw(tenantId));
    }

    private void audit(String tenantId, AuditActionEnum action, String targetId, String details) {
        auditService.record(
                PortalEnum.OPS.code(),
                SecurityUtils.requireContext().getAccountId(),
                "OPS",
                tenantId,
                action.name(),
                "tenant_governance",
                targetId,
                null,
                details);
    }

    public record GovernanceView(List<QuotaView> quotas, List<FlagView> flags, TenantConfig config) {}

    public record QuotaRequest(@NotBlank String quotaKey, Long limitValue) {}

    public record FlagRequest(@NotBlank String flagKey, boolean enabled, String configJson) {}

    public record ConfigRequest(
            String appName, String logoUrl, String primaryColor, String loginSlogan, String supportPhone) {}
}
