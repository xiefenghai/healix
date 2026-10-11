package com.healix.core.job.handler;

/** 平台级定时任务 Handler；禁止依赖 HTTP RequestContext。 */
public interface JobHandler {

    String jobCode();

    JobResult execute(JobContext ctx) throws Exception;
}
