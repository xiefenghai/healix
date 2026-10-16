package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.reward.service.RewardChallengeService;
import com.healix.core.reward.service.RewardService;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 周挑战：每周一结算上周并发放本周挑战。
 *
 * <p>非周一运行时也幂等：只确保本周挑战存在。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardWeeklyJobHandler implements JobHandler {

    private final RewardService rewardService;
    private final RewardChallengeService challengeService;
    private final RewardSourceMapper sourceMapper;

    @Override
    public String jobCode() {
        return JobCatalog.REWARD_WEEKLY;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate today = LocalDate.now(ctx.clock());
        LocalDate thisWeek = RewardChallengeService.weekStart(today);
        LocalDate lastWeek = thisWeek.minusDays(7);
        boolean monday = today.getDayOfWeek() == DayOfWeek.MONDAY;

        Map<String, Boolean> tenantOn = new HashMap<>();
        int settled = 0;
        int awarded = 0;
        int created = 0;
        int failed = 0;
        List<Map<String, String>> bound = sourceMapper.listBoundPeople();
        for (Map<String, String> row : bound) {
            String tenantId = row.get("tenantId");
            String peopleId = row.get("peopleId");
            boolean on = tenantOn.computeIfAbsent(tenantId, rewardService::tenantEnabled);
            if (!on) {
                continue;
            }
            try {
                if (monday) {
                    if (challengeService.settleWeek(tenantId, peopleId, lastWeek)) {
                        awarded++;
                    }
                    settled++;
                }
                if (challengeService.ensureWeekChallenge(tenantId, peopleId, today)) {
                    created++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("REWARD_WEEKLY failed tenant={} people={}: {}", tenantId, peopleId, ex.getMessage());
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("weekStart", thisWeek.toString());
        detail.put("monday", monday);
        detail.put("settledPeople", settled);
        detail.put("awarded", awarded);
        detail.put("created", created);
        detail.put("failed", failed);
        String summary = "周挑战：新建 " + created + (monday ? "，结算达成 " + awarded : "") + "，失败 " + failed;
        log.info("REWARD_WEEKLY done: {}", summary);
        return JobResult.of(summary, detail);
    }
}
