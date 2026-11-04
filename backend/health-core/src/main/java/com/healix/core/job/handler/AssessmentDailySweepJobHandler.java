package com.healix.core.job.handler;

import com.healix.core.assessment.service.AssessmentJobService;
import com.healix.core.assessment.service.AssessmentJobService.Counts;
import com.healix.core.job.catalog.JobCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AssessmentDailySweepJobHandler implements JobHandler {

    private final AssessmentJobService jobService;

    @Override
    public String jobCode() {
        return JobCatalog.ASSESSMENT_DAILY_SWEEP;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        int batchSize = ctx.params() != null ? ctx.params().path("batchSize").asInt(500) : 500;
        boolean quiet = jobService.needsBootstrap();
        Counts counts = jobService.sweepAll(batchSize, quiet, ctx::timedOut);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recomputed", counts.processed());
        detail.put("failed", counts.failed());
        detail.put("bootstrap", quiet);
        detail.put("timedOut", ctx.timedOut());
        return JobResult.of("全量重算 " + counts.processed() + " 人（失败 " + counts.failed() + "）", detail);
    }
}
