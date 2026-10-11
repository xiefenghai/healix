package com.healix.core.job.support;

import com.healix.common.exception.BusinessException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.util.StringUtils;

public final class JobCronSupport {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(1);

    private JobCronSupport() {}

    public static String normalize(String cron) {
        if (!StringUtils.hasText(cron)) {
            return cron;
        }
        return cron.trim().replace('?', '*');
    }

    public static CronExpression parse(String cron) {
        String normalized = normalize(cron);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException("Cron 不能为空");
        }
        try {
            return CronExpression.parse(normalized);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("无效的 Cron 表达式");
        }
    }

    public static void assertMinInterval(String cron) {
        CronExpression expr = parse(cron);
        ZonedDateTime t0 = ZonedDateTime.now(ZONE);
        ZonedDateTime n1 = expr.next(t0);
        if (n1 == null) {
            throw new BusinessException("Cron 无法计算下次执行时间");
        }
        ZonedDateTime n2 = expr.next(n1);
        if (n2 != null && Duration.between(n1, n2).compareTo(MIN_INTERVAL) < 0) {
            throw new BusinessException("Cron 间隔不能短于 1 分钟");
        }
    }

    /** 从 now 起下一个未来触发点（跳过积压）。 */
    public static LocalDateTime nextFutureFire(String cron) {
        CronExpression expr = parse(cron);
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime next = expr.next(now);
        int guard = 0;
        while (next != null && !next.isAfter(now) && guard++ < 10_000) {
            next = expr.next(next);
        }
        if (next == null) {
            throw new BusinessException("Cron 无法计算下次执行时间");
        }
        return next.toLocalDateTime();
    }
}
