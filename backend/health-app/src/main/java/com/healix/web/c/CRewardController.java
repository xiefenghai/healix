package com.healix.web.c;

import com.healix.common.result.ApiResult;
import com.healix.core.reward.dto.RewardViews.Badge;
import com.healix.core.reward.dto.RewardViews.LedgerDay;
import com.healix.core.reward.dto.RewardViews.Peer;
import com.healix.core.reward.dto.RewardViews.Summary;
import com.healix.core.reward.service.RewardQueryService;
import com.healix.security.SecurityUtils;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** C 端激励：今日进度 / 火苗 / 等级、健康豆流水、徽章墙、同期百分位。 */
@RestController
@RequestMapping("/api/c/v1/rewards")
@RequiredArgsConstructor
public class CRewardController {

    private final RewardQueryService rewardQueryService;

    @GetMapping("/summary")
    public ApiResult<Summary> summary() {
        return ApiResult.ok(rewardQueryService.summary(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/ledger")
    public ApiResult<List<LedgerDay>> ledger(@RequestParam(defaultValue = "30") int days) {
        Summary s = rewardQueryService.summary(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId());
        if (!s.enabled()) {
            return ApiResult.ok(List.of());
        }
        return ApiResult.ok(rewardQueryService.ledger(SecurityUtils.requirePatientId(), days));
    }

    @GetMapping("/badges")
    public ApiResult<List<Badge>> badges() {
        return ApiResult.ok(rewardQueryService.badges(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @GetMapping("/peer")
    public ApiResult<Peer> peer() {
        return ApiResult.ok(rewardQueryService.peer(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    @PutMapping("/peer/opt-out")
    public ApiResult<Peer> peerOptOut(@RequestBody(required = false) PeerOptOutRequest req) {
        boolean optOut = req == null || req.optOut() == null || Boolean.TRUE.equals(req.optOut());
        rewardQueryService.setPeerOptOut(
                SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId(), optOut);
        return ApiResult.ok(rewardQueryService.peer(SecurityUtils.requireTenantId(), SecurityUtils.requirePatientId()));
    }

    public record PeerOptOutRequest(Boolean optOut) {}
}
