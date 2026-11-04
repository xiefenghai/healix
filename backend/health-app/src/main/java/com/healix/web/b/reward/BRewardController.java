package com.healix.web.b.reward;

import com.healix.common.result.ApiResult;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.reward.dto.RewardViews.KudosResult;
import com.healix.core.reward.dto.RewardViews.StaffView;
import com.healix.core.reward.service.RewardQueryService;
import com.healix.core.reward.service.RewardService;
import com.healix.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** B 端患者激励：汇总 / 徽章 / 流水、一键点赞、单患者开关。 */
@RestController
@RequestMapping("/api/b/v1/patients/{peopleId}/rewards")
@RequiredArgsConstructor
public class BRewardController {

    private final RewardQueryService rewardQueryService;
    private final RewardService rewardService;
    private final ArchiveAccessService accessService;

    public record KudosRequest(String message) {}

    public record EnabledRequest(Boolean enabled) {}

    @GetMapping
    public ApiResult<StaffView> view(@PathVariable String peopleId) {
        String tenantId = access(peopleId);
        return ApiResult.ok(rewardQueryService.staffView(tenantId, peopleId, SecurityUtils.requireStaffId()));
    }

    @PostMapping("/kudos")
    public ApiResult<KudosResult> kudos(@PathVariable String peopleId, @RequestBody(required = false) KudosRequest req) {
        String tenantId = access(peopleId);
        return ApiResult.ok(rewardQueryService.kudos(
                tenantId,
                SecurityUtils.requireCurrentOrgId(),
                peopleId,
                SecurityUtils.requireStaffId(),
                req == null ? null : req.message()));
    }

    @PutMapping("/enabled")
    public ApiResult<StaffView> enabled(@PathVariable String peopleId, @RequestBody EnabledRequest req) {
        String tenantId = access(peopleId);
        rewardService.setPatientEnabled(tenantId, peopleId, req == null || !Boolean.FALSE.equals(req.enabled()));
        return ApiResult.ok(rewardQueryService.staffView(tenantId, peopleId, SecurityUtils.requireStaffId()));
    }

    private String access(String peopleId) {
        String tenantId = SecurityUtils.requireTenantId();
        accessService.assertStaffCanAccessPeople(tenantId, SecurityUtils.requireCurrentOrgId(), peopleId);
        return tenantId;
    }
}
