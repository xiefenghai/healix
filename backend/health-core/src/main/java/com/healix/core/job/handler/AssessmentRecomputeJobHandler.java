package com.healix.core.job.handler;

import com.healix.core.assessment.service.AssessmentJobService;
import com.healix.core.assessment.service.AssessmentJobService.Counts;
import com.healix.core.job.catalog.JobCatalog;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AssessmentRecomputeJobHandler implements JobHandler {

    private final AssessmentJobService jobService;

    @Override
    public String jobCode() {
        return JobCatalog.ASSESSMENT_RECOMPUTE;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        int batchSize = ctx.params() != null ? ctx.params().path("batchSize").asInt(200) : 200;
        Counts counts = jobService.recomputeDirty(batchSize, ctx::timedOut);
        Map<String, Integer> requests = jobService.handleRequests(LocalDateTime.now(ctx.clock()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("recomputed", counts.processed());
        detail.put("failed", counts.failed());
        detail.putAll(requests);
        return JobResult.of(
                "重算 " + counts.processed() + " 人（失败 " + counts.failed() + "），问卷提醒 "
                        + requests.get("reminded") + " 条，过期 " + requests.get("expired") + " 条",
                detail);
    }
}
