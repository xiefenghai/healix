package com.healix.core.assessment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 最新结果表为空（首次上线）时后台静默全量回填，不触发升级 / 疑似任务。 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "healix.assessment.bootstrap-on-startup", havingValue = "true", matchIfMissing = true)
public class AssessmentBootstrapRunner implements ApplicationRunner {

    private final AssessmentJobService jobService;

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::bootstrap, "assessment-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private void bootstrap() {
        try {
            if (!jobService.needsBootstrap()) {
                return;
            }
            log.info("assessment bootstrap: latest table empty, start quiet sweep");
            AssessmentJobService.Counts counts = jobService.sweepAll(500, true, () -> false);
            log.info("assessment bootstrap done processed={} failed={}", counts.processed(), counts.failed());
        } catch (RuntimeException e) {
            log.warn("assessment bootstrap failed: {}", e.getMessage());
        }
    }
}
