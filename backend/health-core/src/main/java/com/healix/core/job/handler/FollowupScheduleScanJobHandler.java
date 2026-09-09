package com.healix.core.job.handler;

import com.healix.core.followup.service.FollowupScheduleService;
import com.healix.core.followup.service.FollowupScheduleService.ScheduleCounts;
import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.support.JobCronSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupScheduleScanJobHandler implements JobHandler {

    private final FollowupScheduleService followupScheduleService;

    @Override
    public String jobCode() {
        return JobCatalog.FOLLOWUP_SCHEDULE_SCAN;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate day = LocalDate.now(ctx.clock());
        ScheduleCounts counts = followupScheduleService.scanAll(day);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scanDay", day.toString());
        detail.put("timezone", JobCronSupport.ZONE.getId());
        detail.put("orgs", counts.orgs());
        detail.put("scanned", counts.scanned());
        detail.put("created", counts.created());
        detail.put("skippedOpen", counts.skippedOpen());
        detail.put("skippedNotDue", counts.skippedNotDue());
        String summary = "扫描 " + counts.orgs() + " 个机构 / " + counts.scanned()
                + " 人，新排期 " + counts.created() + "，已有待办 " + counts.skippedOpen();
        log.info("FOLLOWUP_SCHEDULE_SCAN done: {}", summary);
        return JobResult.of(summary, detail);
    }
}
