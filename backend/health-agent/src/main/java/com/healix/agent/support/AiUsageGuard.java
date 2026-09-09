package com.healix.agent.support;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.govern.service.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * AI 能力的开关与用量闸门：先看功能开关，再占用当月配额。
 *
 * <p>配额在调用前占用而非成功后计数：LLM 一旦发出请求，成本就已经产生，
 * 按结果计费会让失败重试变成免费刷量。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiUsageGuard {

    private final FeatureFlagService featureFlagService;
    private final QuotaService quotaService;

    /** 从请求上下文取租户；后台任务里没有上下文时返回 null，表示不做租户级管控。 */
    public static String currentTenantId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null) {
            return null;
        }
        return StringUtils.hasText(ctx.getTenantId()) ? ctx.getTenantId() : ctx.getHomeTenantId();
    }

    public void check(String tenantId, FeatureFlagKeyEnum flag, QuotaKeyEnum quota) {
        if (!StringUtils.hasText(tenantId)) {
            return;
        }
        featureFlagService.assertEnabled(tenantId, flag);
        quotaService.consume(tenantId, quota, 1);
    }

    /** 从请求上下文推断租户后校验，供拿不到 tenantId 的底层组件使用。 */
    public void checkCurrent(FeatureFlagKeyEnum flag, QuotaKeyEnum quota) {
        check(currentTenantId(), flag, quota);
    }
}
