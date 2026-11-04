package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.medication.service.MedicationReminderScanService;
import com.healix.core.medication.service.MedicationReminderScanService.ScanCounts;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MedicationRemindJobHandler implements JobHandler {

    private final MedicationReminderScanService scanService;

    @Override
    public String jobCode() {
        return JobCatalog.MEDICATION_REMIND;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDateTime now = LocalDateTime.now(ctx.clock());
        ScanCounts counts = scanService.scanAll(now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("at", now.toString());
        detail.put("candidates", counts.candidates());
        detail.put("sent", counts.sent());
        return JobResult.of("扫描 " + counts.candidates() + " 人，发送提醒 " + counts.sent() + " 条", detail);
    }
}
