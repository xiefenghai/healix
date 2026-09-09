package com.healix.core.job.support;

import java.time.Duration;
import java.util.Optional;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.springframework.stereotype.Service;

@Service
public class JobLockService {

    private static final Duration LOCK_AT_LEAST = Duration.ofSeconds(2);

    private final LockProvider lockProvider;

    public JobLockService(LockProvider lockProvider) {
        this.lockProvider = lockProvider;
    }

    public Optional<SimpleLock> tryLock(String jobCode, Duration lockAtMost) {
        LockConfiguration cfg = new LockConfiguration(
                java.time.Instant.now(), "job:lock:" + jobCode, lockAtMost, LOCK_AT_LEAST);
        return lockProvider.lock(cfg);
    }
}
