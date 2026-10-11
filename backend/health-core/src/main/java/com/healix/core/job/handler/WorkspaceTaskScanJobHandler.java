package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import com.healix.core.worktask.service.WorkspaceTaskGenerator.ScanCounts;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkspaceTaskScanJobHandler implements JobHandler {

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;

    @Override
    public String jobCode() {
        return JobCatalog.WORKSPACE_TASK_SCAN;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate scanDay = LocalDate.now(ctx.clock());
        int orgs = 0;
        long expired = 0;
        long teamAssign = 0;
        long planCreate = 0;
        long nudgeOpen = 0;
        long nudgeUpdate = 0;
        long metricOpen = 0;
        long metricMerged = 0;
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            if (ctx.timedOut()) {
                throw new IllegalStateException("任务超时");
            }
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                if (ctx.timedOut()) {
                    throw new IllegalStateException("任务超时");
                }
                ScanCounts c = workspaceTaskGenerator.scanOrg(tenant.getId(), org.getId(), scanDay);
                orgs++;
                expired += c.expired();
                teamAssign += c.teamAssignOpened();
                planCreate += c.planCreateOpened();
                nudgeOpen += c.planNudgeOpened();
                nudgeUpdate += c.planNudgeUpdated();
                metricOpen += c.metricOpened();
                metricMerged += c.metricMerged();
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scanDay", scanDay.toString());
        detail.put("timezone", JobCronSupport.ZONE.getId());
        detail.put("orgs", orgs);
        detail.put("expired", expired);
        detail.put("teamAssignOpened", teamAssign);
        detail.put("planCreateOpened", planCreate);
        detail.put("planNudgeOpened", nudgeOpen);
        detail.put("planNudgeUpdated", nudgeUpdate);
        detail.put("metricOpened", metricOpen);
        detail.put("metricMerged", metricMerged);
        return JobResult.of(
                "扫描 " + orgs + " 个机构，开单 "
                        + (teamAssign + planCreate + nudgeOpen + metricOpen)
                        + " 张",
                detail);
    }
}
