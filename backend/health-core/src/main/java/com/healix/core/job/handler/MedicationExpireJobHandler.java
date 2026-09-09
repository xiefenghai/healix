package com.healix.core.job.handler;

import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MedicationExpireJobHandler implements JobHandler {

    private static final int DEFAULT_BATCH = 500;
    private static final int MAX_ROUNDS = 10_000;

    private final PeopleMedicationMapper medicationMapper;

    @Override
    public String jobCode() {
        return JobCatalog.MEDICATION_EXPIRE;
    }

    @Override
    public JobResult execute(JobContext ctx) {
        int batchSize = resolveBatchSize(ctx);
        LocalDate today = LocalDate.now(ctx.clock());
        long updated = 0;
        int rounds = 0;
        while (rounds++ < MAX_ROUNDS) {
            if (ctx.timedOut()) {
                throw new IllegalStateException("任务超时");
            }
            int n = medicationMapper.expireDueForActiveTenants(today, batchSize);
            updated += n;
            if (n < batchSize) {
                break;
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("updatedRows", updated);
        detail.put("batchSize", batchSize);
        detail.put("asOf", today.toString());
        return JobResult.of("过期用药 " + updated + " 条", detail);
    }

    private static int resolveBatchSize(JobContext ctx) {
        if (ctx.params() != null && ctx.params().has("batchSize")) {
            int n = ctx.params().path("batchSize").asInt(DEFAULT_BATCH);
            return n > 0 ? Math.min(n, 2000) : DEFAULT_BATCH;
        }
        return DEFAULT_BATCH;
    }
}
