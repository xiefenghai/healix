package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.reward.mapper.RewardSourceMapper;
import com.healix.core.reward.service.RewardQueryService;
import com.healix.core.reward.service.RewardQueryService.PatientBrief;
import com.healix.core.reward.service.RewardService;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 火苗中断 ≥3 天 → 工作台 PLAN_NUDGE（与方案未完成催办共用，不重复开单）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardFlameNudgeJobHandler implements JobHandler {

    private final RewardService rewardService;
    private final RewardQueryService rewardQueryService;
    private final RewardSourceMapper sourceMapper;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientOrgMembershipMapper membershipMapper;

    @Override
    public String jobCode() {
        return JobCatalog.REWARD_FLAME_NUDGE;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate today = LocalDate.now(ctx.clock());
        Map<String, Boolean> tenantOn = new HashMap<>();
        int scanned = 0;
        int nudged = 0;
        int failed = 0;
        List<Map<String, String>> bound = sourceMapper.listBoundPeople();
        // 按租户批量取 brief
        Map<String, List<String>> byTenant = new LinkedHashMap<>();
        for (Map<String, String> row : bound) {
            byTenant.computeIfAbsent(row.get("tenantId"), k -> new java.util.ArrayList<>()).add(row.get("peopleId"));
        }
        for (Map.Entry<String, List<String>> e : byTenant.entrySet()) {
            String tenantId = e.getKey();
            boolean on = tenantOn.computeIfAbsent(tenantId, rewardService::tenantEnabled);
            if (!on) {
                continue;
            }
            Map<String, PatientBrief> briefs = rewardQueryService.patientBriefs(tenantId, e.getValue());
            for (String peopleId : e.getValue()) {
                PatientBrief b = briefs.get(peopleId);
                if (b == null || b.inactiveDays() == null || b.inactiveDays() < 3) {
                    continue;
                }
                scanned++;
                try {
                    String orgId = resolveOrg(tenantId, peopleId);
                    if (!StringUtils.hasText(orgId)) {
                        continue;
                    }
                    int n = workspaceTaskGenerator.upsertFlameNudge(
                            tenantId, orgId, peopleId, b.inactiveDays(), today);
                    nudged += n > 0 ? 1 : 0;
                } catch (Exception ex) {
                    failed++;
                    log.warn("REWARD_FLAME_NUDGE failed tenant={} people={}: {}", tenantId, peopleId, ex.getMessage());
                }
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scanned", scanned);
        detail.put("nudged", nudged);
        detail.put("failed", failed);
        String summary = "火苗中断跟进：扫描 " + scanned + "，开/更新 " + nudged + "，失败 " + failed;
        log.info("REWARD_FLAME_NUDGE done: {}", summary);
        return JobResult.of(summary, detail);
    }

    private String resolveOrg(String tenantId, String peopleId) {
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        if (a != null && StringUtils.hasText(a.getPrimaryOrgId())) {
            return a.getPrimaryOrgId();
        }
        List<PatientOrgMembership> ms = membershipMapper.listActiveByPeople(peopleId);
        return ms.isEmpty() ? null : ms.get(0).getOrgId();
    }
}
