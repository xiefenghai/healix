package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.notify.service.NotifyReminderScanService;
import com.healix.core.notify.service.NotifyReminderScanService.ScanCounts;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyReminderScanJobHandler implements JobHandler {

    private final NotifyReminderScanService notifyReminderScanService;

    @Override
    public String jobCode() {
        return JobCatalog.NOTIFY_REMINDER_SCAN;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate day = LocalDate.now(ctx.clock());
        ScanCounts counts = notifyReminderScanService.scanAll(day);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("scanDay", day.toString());
        detail.put("timezone", JobCronSupport.ZONE.getId());
        detail.put("orgs", counts.orgs());
        detail.put("candidates", counts.candidates());
        detail.put("created", counts.created());
        detail.put("upserted", counts.upserted());
        detail.put("skippedNoRecipient", counts.skippedNoRecipient());
        String summary = "扫描 " + counts.orgs() + " 个机构，待办 " + counts.candidates()
                + " 人，新建 " + counts.created() + " / 更新 " + counts.upserted();
        log.info("NOTIFY_REMINDER_SCAN done: {}", summary);
        return JobResult.of(summary, detail);
    }
}
