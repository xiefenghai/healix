package com.healix.web.b.followup;

import com.healix.common.context.RequestContext;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.ApiResult;
import com.healix.core.followup.service.FollowupScheduleRuleService;
import com.healix.core.followup.service.FollowupScheduleRuleService.RuleView;
import com.healix.core.followup.service.FollowupScheduleRuleService.SaveCommand;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 定期随访排期规则，仅租户管理员可配置。 */
@RestController
@RequestMapping("/api/b/v1/followup-schedule-rules")
@RequiredArgsConstructor
public class BFollowupScheduleRuleController {

    private final FollowupScheduleRuleService ruleService;

    @GetMapping
    public ApiResult<RulesResponse> list() {
        var ctx = requireTenantAdmin();
        return ApiResult.ok(new RulesResponse(
                ruleService.list(ctx.getTenantId()),
                FollowupScheduleRuleService.DEFAULT_INTERVAL_WITH_PLAN,
                FollowupScheduleRuleService.DEFAULT_INTERVAL_NO_PLAN));
    }

    @PostMapping
    public ApiResult<RuleView> save(@RequestBody SaveCommand request) {
        var ctx = requireTenantAdmin();
        return ApiResult.ok(ruleService.save(ctx.getTenantId(), ctx.getStaffId(), request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable String id) {
        var ctx = requireTenantAdmin();
        ruleService.delete(ctx.getTenantId(), id);
        return ApiResult.ok(null);
    }

    private static RequestContext requireTenantAdmin() {
        var ctx = SecurityUtils.requireContext();
        if (!ctx.getRoles().contains(StaffRoleEnum.TENANT_ADMIN.name())) {
            throw new BusinessException(403, "仅租户管理员可配置随访排期");
        }
        return ctx;
    }

    public record RulesResponse(List<RuleView> rules, int defaultIntervalWithPlan, int defaultIntervalNoPlan) {}
}
