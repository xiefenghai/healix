package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.reward.service.RewardService;
import com.healix.core.reward.service.RewardService.SettleOutcome;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 激励终结算：处理 D-2（今天、昨天仍可补打）。先按源表对账流水，再定火苗、保护卡、全勤日、徽章与等级，
 * 有新徽章 / 用卡 / 升级时合并推送一条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardSettleJobHandler implements JobHandler {

    private final RewardService rewardService;
    private final RewardSourceMapper sourceMapper;

    @Override
    public String jobCode() {
        return JobCatalog.REWARD_SETTLE;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate target = LocalDate.now(ctx.clock()).minusDays(2);
        Map<String, Boolean> tenantOn = new HashMap<>();
        int people = 0;
        int days = 0;
        int badges = 0;
        int failed = 0;
        List<Map<String, String>> bound = sourceMapper.listBoundPeople();
        for (Map<String, String> row : bound) {
            String tenantId = row.get("tenantId");
            String peopleId = row.get("peopleId");
            boolean on = tenantOn.computeIfAbsent(tenantId, rewardService::tenantEnabled);
            try {
                SettleOutcome o = rewardService.settleUntil(tenantId, peopleId, target, on);
                if (o.settledDays() > 0) {
                    people++;
                    days += o.settledDays();
                    badges += o.newBadges().size();
                    rewardService.pushSettleOutcome(tenantId, peopleId, o);
                }
            } catch (Exception ex) {
                failed++;
                log.warn("REWARD_SETTLE failed tenant={} people={}: {}", tenantId, peopleId, ex.getMessage());
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("settleDay", target.toString());
        detail.put("boundPeople", bound.size());
        detail.put("settledPeople", people);
        detail.put("settledDays", days);
        detail.put("newBadges", badges);
        detail.put("failed", failed);
        String summary = "结算 " + target + "：" + people + " 人 / " + days + " 天，新徽章 " + badges + "，失败 " + failed;
        log.info("REWARD_SETTLE done: {}", summary);
        return JobResult.of(summary, detail);
    }
}
