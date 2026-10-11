package com.healix.config;

import com.healix.common.domain.EntityMeta;
import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.catalog.JobCatalogSpec;
import com.healix.core.job.domain.SysJobDef;
import com.healix.core.job.handler.JobHandlerRegistry;
import com.healix.core.job.mapper.SysJobDefMapper;
import com.healix.core.job.support.JobCronSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 将 JobCatalog 中尚未入库的任务补建（默认启用），不覆盖已有配置。 */
@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class JobCatalogSeedRunner implements ApplicationRunner {

    private final JobCatalog jobCatalog;
    private final JobHandlerRegistry handlerRegistry;
    private final SysJobDefMapper jobDefMapper;

    @Override
    public void run(ApplicationArguments args) {
        int added = 0;
        for (JobCatalogSpec spec : jobCatalog.all()) {
            if (handlerRegistry.find(spec.jobCode()).isEmpty()) {
                continue;
            }
            if (jobDefMapper.findByJobCode(spec.jobCode()) != null) {
                continue;
            }
            String cron = JobCronSupport.normalize(spec.defaultCron());
            SysJobDef row = new SysJobDef();
            row.setJobCode(spec.jobCode());
            row.setDisplayName(spec.displayName());
            row.setCronExpr(cron);
            row.setTimezone(JobCronSupport.ZONE.getId());
            row.setEnabled(1);
            row.setParamsJson(spec.defaultParamsJson());
            row.setNextFireAt(JobCronSupport.nextFutureFire(cron));
            EntityMeta.onCreate(row);
            jobDefMapper.insert(row);
            added++;
            log.info("Seeded job def {}", spec.jobCode());
        }
        if (added > 0) {
            log.info("JobCatalogSeedRunner added {} job def(s)", added);
        }
    }
}
