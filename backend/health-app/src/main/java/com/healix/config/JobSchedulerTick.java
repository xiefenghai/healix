package com.healix.config;

import com.healix.core.job.service.JobSchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JobSchedulerTick {

    private final JobSchedulerService jobSchedulerService;

    @Scheduled(fixedDelayString = "${healix.job.tick-ms:60000}")
    public void tick() {
        jobSchedulerService.tickDueJobs();
    }
}
