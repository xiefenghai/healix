package com.healix.security;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.exception.BusinessException;
import com.healix.common.exception.UnauthorizedException;

public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static RequestContext requireContext() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getAccountId() == null) {
            throw new UnauthorizedException("未登录");
        }
        return ctx;
    }

    public static String requirePatientId() {
        String patientId = requireContext().getPatientId();
        if (patientId == null) {
            throw new BusinessException(400, "请先添加或选择就诊人");
        }
        return patientId;
    }


    public static String requireStaffId() {
        String staffId = requireContext().getStaffId();
        if (staffId == null) {
            throw new UnauthorizedException("缺少员工上下文");
        }
        return staffId;
    }

    public static String requireTenantId() {
        String tenantId = requireContext().getTenantId();
        if (tenantId == null) {
            throw new UnauthorizedException("缺少租户上下文");
        }
        return tenantId;
    }

    public static String requireCurrentOrgId() {
        String orgId = requireContext().getCurrentOrgId();
        if (orgId == null) {
            throw new UnauthorizedException("请先选择工作机构");
        }
        return orgId;
    }
}
