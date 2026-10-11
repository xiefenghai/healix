package com.healix.core.job.handler;

import com.healix.core.adherence.service.AdherenceSnapshotService;
import com.healix.core.adherence.service.AdherenceSnapshotService.SnapshotCounts;
import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.support.JobCronSupport;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 固化前一天的依从性汇总；凌晨跑，当天数据已不再变动。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdherenceSnapshotJobHandler implements JobHandler {

    private final AdherenceSnapshotService adherenceSnapshotService;

    @Override
    public String jobCode() {
        return JobCatalog.ADHERENCE_SNAPSHOT;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        LocalDate target = LocalDate.now(ctx.clock()).minusDays(1);
        SnapshotCounts counts = adherenceSnapshotService.snapshotAll(target);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("snapshotDay", target.toString());
        detail.put("timezone", JobCronSupport.ZONE.getId());
        detail.put("orgs", counts.orgs());
        detail.put("rows", counts.rows());
        detail.put("failed", counts.failed());
        String summary = "快照 " + target + "：" + counts.orgs() + " 个机构，写入 " + counts.rows()
                + " 行，失败 " + counts.failed();
        log.info("ADHERENCE_SNAPSHOT done: {}", summary);
        return JobResult.of(summary, detail);
    }
}
