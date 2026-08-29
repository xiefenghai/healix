package com.healix.core.revision.aspect;

import com.healix.core.revision.annotation.RecordFieldRevision;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 档案修订注解标记；字段 diff 与 metadata 同步在 Service 内同事务完成。 */
@Slf4j
@Aspect
@Component
@Order(100)
public class FieldRevisionAspect {

    @Around("@annotation(revision)")
    public Object around(ProceedingJoinPoint pjp, RecordFieldRevision revision) throws Throwable {
        log.debug("Archive write bizType={} method={}", revision.bizType(), pjp.getSignature().getName());
        return pjp.proceed();
    }
}
