package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.reward.service.RewardService;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 晚间火苗提醒：火苗 ≥3 天且今天还没有任何行为的患者，每天最多 1 条激励推送。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardEveningRemindJobHandler implements JobHandler {

    private final RewardService rewardService;
    private final RewardSourceMapper sourceMapper;

    @Override
    public String jobCode() {
        return JobCatalog.REWARD_EVENING_REMIND;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        Map<String, Boolean> tenantOn = new HashMap<>();
        int sent = 0;
        int failed = 0;
        List<Map<String, String>> bound = sourceMapper.listBoundPeople();
        for (Map<String, String> row : bound) {
            String tenantId = row.get("tenantId");
            if (!tenantOn.computeIfAbsent(tenantId, rewardService::tenantEnabled)) {
                continue;
            }
            try {
                if (rewardService.remindEvening(tenantId, row.get("peopleId"))) {
                    sent++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("REWARD_EVENING_REMIND failed people={}: {}", row.get("peopleId"), ex.getMessage());
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("boundPeople", bound.size());
        detail.put("sent", sent);
        detail.put("failed", failed);
        String summary = "火苗提醒：发送 " + sent + " 人，失败 " + failed;
        log.info("REWARD_EVENING_REMIND done: {}", summary);
        return JobResult.of(summary, detail);
    }
}
