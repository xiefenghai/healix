package com.healix.core.job.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.job.domain.SysJobDef;
import com.healix.core.job.domain.SysJobRun;
import com.healix.core.job.enums.JobRunStatus;
import com.healix.core.job.enums.JobTriggerType;
import com.healix.core.job.handler.JobContext;
import com.healix.core.job.handler.JobHandler;
import com.healix.core.job.handler.JobHandlerRegistry;
import com.healix.core.job.handler.JobResult;
import com.healix.core.job.mapper.SysJobDefMapper;
import com.healix.core.job.mapper.SysJobRunMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.job.support.JobLockService;
import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobSchedulerService {

    private static final Duration DEFAULT_LOCK_AT_MOST = Duration.ofMinutes(10);

    private final SysJobDefMapper jobDefMapper;
    private final SysJobRunMapper jobRunMapper;
    private final JobHandlerRegistry handlerRegistry;
    private final JobLockService jobLockService;

    @Value("${healix.job.timeout-minutes:8}")
    private int timeoutMinutes;

    public void tickDueJobs() {
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        for (SysJobDef def : jobDefMapper.listDue(now)) {
            try {
                Optional<SimpleLock> lock = jobLockService.tryLock(def.getJobCode(), DEFAULT_LOCK_AT_MOST);
                if (lock.isEmpty()) {
                    log.debug("Skip due job {}, lock held", def.getJobCode());
                    continue;
                }
                try {
                    runLocked(def, JobTriggerType.SCHEDULE, null);
                } finally {
                    lock.get().unlock();
                }
            } catch (Exception ex) {
                log.error("Scheduled job {} tick failed", def.getJobCode(), ex);
            }
        }
    }

    /** 手动整轮触发；抢不到锁视为执行中。 */
    public SysJobRun triggerManual(SysJobDef def, String opsAccountId) {
        Optional<SimpleLock> lock;
        try {
            lock = jobLockService.tryLock(def.getJobCode(), DEFAULT_LOCK_AT_MOST);
        } catch (RuntimeException ex) {
            throw new BusinessException(503, "调度锁不可用，请检查 Redis");
        }
        if (lock.isEmpty()) {
            throw new BusinessException("任务执行中");
        }
        try {
            return runLocked(def, JobTriggerType.MANUAL, opsAccountId);
        } finally {
            lock.get().unlock();
        }
    }

    private SysJobRun runLocked(SysJobDef def, JobTriggerType triggerType, String opsAccountId) {
        LocalDateTime started = LocalDateTime.now(JobCronSupport.ZONE);
        SysJobRun run = new SysJobRun();
        run.setJobDefId(def.getId());
        run.setJobCode(def.getJobCode());
        run.setTriggerType(triggerType.name());
        run.setStatus(JobRunStatus.RUNNING.name());
        run.setStartedAt(started);
        run.setInstanceId(instanceId());
        run.setTriggeredBy(opsAccountId);
        EntityMeta.onCreate(run);
        jobRunMapper.insert(run);

        Clock clock = Clock.system(JobCronSupport.ZONE);
        Instant deadline = clock.instant().plus(Duration.ofMinutes(Math.max(1, timeoutMinutes)));
        JsonNode params = JsonUtils.readTree(def.getParamsJson());
        JobContext ctx = new JobContext(def.getJobCode(), run.getId(), triggerType.name(), params, clock, deadline);

        String status;
        String message;
        String detailJson = null;
        try {
            JobHandler handler = handlerRegistry
                    .find(def.getJobCode())
                    .orElseThrow(() -> new IllegalStateException("未注册 Handler: " + def.getJobCode()));
            JobResult result = handler.execute(ctx);
            status = JobRunStatus.SUCCESS.name();
            message = trim(result == null ? "ok" : result.message(), 512);
            Map<String, Object> detail = result == null ? Map.of() : result.detail();
            if (detail != null && !detail.isEmpty()) {
                detailJson = JsonUtils.toJson(detail);
            }
        } catch (Exception ex) {
            log.error("Job {} failed", def.getJobCode(), ex);
            status = JobRunStatus.FAILED.name();
            message = trim(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(), 512);
        }

        LocalDateTime finished = LocalDateTime.now(JobCronSupport.ZONE);
        run.setStatus(status);
        run.setFinishedAt(finished);
        run.setMessage(message);
        run.setDetailJson(detailJson);
        EntityMeta.onUpdate(run);
        jobRunMapper.updateFinish(run);

        def.setLastFireAt(started);
        def.setLastStatus(status);
        def.setLastMessage(message);
        if (triggerType == JobTriggerType.SCHEDULE) {
            try {
                def.setNextFireAt(JobCronSupport.nextFutureFire(def.getCronExpr()));
            } catch (RuntimeException ex) {
                log.warn("Failed to advance next_fire_at for {}", def.getJobCode(), ex);
            }
        }
        EntityMeta.onUpdate(def);
        jobDefMapper.updateFireState(def);
        return run;
    }

    private static String instanceId() {
        try {
            return ManagementFactory.getRuntimeMXBean().getName();
        } catch (Exception ex) {
            return "unknown";
        }
    }

    private static String trim(String v, int max) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
